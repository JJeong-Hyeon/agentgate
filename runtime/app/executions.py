import logging
import secrets
import threading
import uuid
from typing import Annotated, Any, Literal

from fastapi import (
    APIRouter,
    BackgroundTasks,
    Depends,
    Header,
    HTTPException,
    Request,
    Response,
    status,
)
from langgraph.graph.state import CompiledStateGraph
from langgraph.types import Command
from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from app.agents.resolve import attach_tool_schemas
from app.config import Settings, get_settings
from app.dsl import validate_workflow
from app.dsl.compiler import CompileError
from app.events import EventReporter, run_graph
from app.runner import UnknownExecution, WorkflowRunner
from app.tools_api import Catalog

log = logging.getLogger(__name__)
router = APIRouter(prefix="/runtime/executions", tags=["executions"])


class ExecutionRequest(BaseModel):
    # AgentGate (Spring) sends camelCase; snake_case is accepted too.
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    task: str = Field(min_length=1)
    # Workflow DSL to run (see app/dsl/schema.py).
    workflow: dict[str, Any]
    # Caller-chosen id (AgentGate creates the execution record first); generated if omitted.
    execution_id: str | None = Field(default=None, pattern=r"^[A-Za-z0-9-]{1,64}$")
    # Return 202 immediately and run in the background; progress is reported as events.
    background: bool = False


class ExecutionResponse(BaseModel):
    execution_id: str
    status: Literal["RUNNING", "WAITING_APPROVAL", "COMPLETED", "STOPPED", "RESUMING"]
    waiting_approval_id: int | None = None
    state: dict | None = None


class ResumeRequest(BaseModel):
    # Sent by AgentGate (Spring), which uses camelCase JSON.
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    approval_id: int
    decision: Literal["APPROVED", "REJECTED", "EXPIRED"]
    # What AgentGate recorded for the approval; the call runs only if it still matches.
    payload_hash: str | None = None


def get_runner(request: Request) -> WorkflowRunner:
    return request.app.state.runner


def get_reporter(request: Request) -> EventReporter:
    return getattr(request.app.state, "reporter", None) or EventReporter("", "")


Runner = Annotated[WorkflowRunner, Depends(get_runner)]
Reporter = Annotated[EventReporter, Depends(get_reporter)]

# Executions with a resume in flight; a second resume for the same one is a conflict.
# Executions this process is running in the background (started, resumed or recovered). After a
# restart it is empty, which is how AgentGate tells an interrupted execution from a slow one.
_running: set[str] = set()
_running_lock = threading.Lock()
_TOOL_NODE_TYPES = {"HTTP_TOOL", "MCP_TOOL"}


def require_runtime_token(
    settings: Annotated[Settings, Depends(get_settings)],
    x_runtime_token: Annotated[str | None, Header()] = None,
) -> None:
    if not settings.runtime_token:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Resume is disabled")
    require_token_if_configured(settings, x_runtime_token)


def require_token_if_configured(
    settings: Annotated[Settings, Depends(get_settings)],
    x_runtime_token: Annotated[str | None, Header()] = None,
) -> None:
    """Every runtime API answers only AgentGate once RUNTIME_TOKEN is set; without it (local
    development) they stay open."""
    if not settings.runtime_token:
        return
    if not x_runtime_token or not secrets.compare_digest(x_runtime_token, settings.runtime_token):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid runtime token")


def _config(execution_id: str) -> dict:
    return {"configurable": {"thread_id": execution_id}}


def _graph_for(runner: WorkflowRunner, execution_id: str) -> CompiledStateGraph:
    try:
        return runner.graph_for_execution(execution_id)
    except UnknownExecution as e:
        raise HTTPException(
            status.HTTP_404_NOT_FOUND, f"Execution '{execution_id}' not found"
        ) from e


def _describe(graph: CompiledStateGraph, execution_id: str) -> ExecutionResponse:
    snapshot = graph.get_state(_config(execution_id))
    if not snapshot.values:
        raise HTTPException(status.HTTP_404_NOT_FOUND, f"Execution '{execution_id}' not found")
    if snapshot.interrupts:
        return ExecutionResponse(
            execution_id=execution_id,
            status="WAITING_APPROVAL",
            waiting_approval_id=snapshot.interrupts[0].value.get("approval_id"),
            state=snapshot.values,
        )
    if snapshot.next:
        status_ = "RUNNING"
    else:
        status_ = "STOPPED" if snapshot.values.get("stopped") else "COMPLETED"
    return ExecutionResponse(execution_id=execution_id, status=status_, state=snapshot.values)


@router.post("", status_code=status.HTTP_201_CREATED)
def create_execution(
    body: ExecutionRequest,
    runner: Runner,
    reporter: Reporter,
    catalog: Catalog,
    background: BackgroundTasks,
    response: Response,
) -> ExecutionResponse:
    workflow, issues = validate_workflow(body.workflow)
    if issues:
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_CONTENT,
            {"message": "Invalid workflow", "errors": [i.model_dump() for i in issues]},
        )
    try:
        workflow = attach_tool_schemas(workflow, catalog)
        graph = runner.graph_for_workflow(workflow)
    except CompileError as e:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, str(e)) from e
    dsl = workflow.model_dump(mode="json", by_alias=True)
    initial = {"task": body.task, "workflow": dsl, "revisions": {}}

    execution_id = body.execution_id or str(uuid.uuid4())
    if runner.exists(execution_id):
        raise HTTPException(status.HTTP_409_CONFLICT, f"Execution '{execution_id}' already exists")

    if body.background:
        with _running_lock:
            _running.add(execution_id)
        background.add_task(_run_logged, graph, initial, execution_id, reporter)
        response.status_code = status.HTTP_202_ACCEPTED
        return ExecutionResponse(execution_id=execution_id, status="RUNNING")

    # Runs synchronously until it completes or pauses for approval.
    run_graph(graph, initial, _config(execution_id), reporter)
    return _describe(graph, execution_id)


class ActiveExecutions(BaseModel):
    execution_ids: list[str]


@router.get("/active")
def active_executions() -> ActiveExecutions:
    with _running_lock:
        return ActiveExecutions(execution_ids=sorted(_running))


@router.get("/{execution_id}")
def get_execution(execution_id: str, runner: Runner) -> ExecutionResponse:
    return _describe(_graph_for(runner, execution_id), execution_id)


@router.delete("/{execution_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_execution(execution_id: str, runner: Runner) -> None:
    """Forgets a finished execution's checkpoints; deleting an unknown execution is a no-op."""
    runner.forget(execution_id)


@router.post(
    "/{execution_id}/resume",
    status_code=status.HTTP_202_ACCEPTED,
    dependencies=[Depends(require_runtime_token)],
)
def resume_execution(
    execution_id: str,
    body: ResumeRequest,
    runner: Runner,
    reporter: Reporter,
    background: BackgroundTasks,
) -> ExecutionResponse:
    graph = _graph_for(runner, execution_id)
    with _running_lock:
        current = _describe(graph, execution_id)
        if execution_id in _running or current.status != "WAITING_APPROVAL":
            raise HTTPException(status.HTTP_409_CONFLICT, "Execution is not waiting for approval")
        if current.waiting_approval_id != body.approval_id:
            raise HTTPException(
                status.HTTP_409_CONFLICT,
                f"Execution is waiting for approval {current.waiting_approval_id}",
            )
        _running.add(execution_id)

    resume = Command(resume={"decision": body.decision, "payloadHash": body.payload_hash})
    background.add_task(_run_logged, graph, resume, execution_id, reporter)
    return ExecutionResponse(execution_id=execution_id, status="RESUMING")


@router.post("/{execution_id}/recover", status_code=status.HTTP_202_ACCEPTED)
def recover_execution(
    execution_id: str, runner: Runner, reporter: Reporter, background: BackgroundTasks
) -> ExecutionResponse:
    """Continues an execution a restart interrupted, from its last checkpoint. 404 when nothing was
    saved, 409 when it is not interrupted, 422 when it stopped in the middle of a tool call: the
    tool may already have run, so running it again could repeat its effect."""
    graph = _graph_for(runner, execution_id)
    with _running_lock:
        current = _describe(graph, execution_id)
        if execution_id in _running or current.status != "RUNNING":
            raise HTTPException(status.HTTP_409_CONFLICT, f"Execution is {current.status}")
        pending = graph.get_state(_config(execution_id)).next
        tools = _tool_steps(current.state.get("workflow") or {})
        unsafe = [step for step in pending if step in tools or step.endswith(".execute")]
        if unsafe:
            raise HTTPException(
                status.HTTP_422_UNPROCESSABLE_CONTENT,
                f"Interrupted while running {', '.join(unsafe)}; it may already have taken effect",
            )
        _running.add(execution_id)
    background.add_task(_run_logged, graph, None, execution_id, reporter)
    return ExecutionResponse(execution_id=execution_id, status="RUNNING")


def _tool_steps(workflow: dict) -> set[str]:
    return {n["id"] for n in workflow.get("nodes", []) if n.get("type") in _TOOL_NODE_TYPES}


def _run_logged(
    graph: CompiledStateGraph, graph_input: Any, execution_id: str, reporter: EventReporter
) -> None:
    # Background runs have no caller to raise to; the failure was reported as an event.
    try:
        run_graph(graph, graph_input, _config(execution_id), reporter)
    except Exception:
        log.exception("Execution %s failed", execution_id)
    finally:
        with _running_lock:
            _running.discard(execution_id)
