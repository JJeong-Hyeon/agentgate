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

from app.config import Settings, get_settings
from app.dsl import validate_workflow
from app.dsl.compiler import CompileError
from app.events import EventReporter, run_graph
from app.runner import UnknownExecution, WorkflowRunner

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
    status: Literal["RUNNING", "WAITING_APPROVAL", "COMPLETED", "RESUMING"]
    waiting_approval_id: int | None = None
    state: dict | None = None


class ResumeRequest(BaseModel):
    # Sent by AgentGate (Spring), which uses camelCase JSON.
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    approval_id: int
    decision: Literal["APPROVED", "REJECTED"]


def get_runner(request: Request) -> WorkflowRunner:
    return request.app.state.runner


def get_reporter(request: Request) -> EventReporter:
    return getattr(request.app.state, "reporter", None) or EventReporter("", "")


Runner = Annotated[WorkflowRunner, Depends(get_runner)]
Reporter = Annotated[EventReporter, Depends(get_reporter)]

# Executions with a resume in flight; a second resume for the same one is a conflict.
_resuming: set[str] = set()
_resuming_lock = threading.Lock()


def require_runtime_token(
    settings: Annotated[Settings, Depends(get_settings)],
    x_runtime_token: Annotated[str | None, Header()] = None,
) -> None:
    if not settings.runtime_token:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Resume is disabled")
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
    return ExecutionResponse(
        execution_id=execution_id,
        status="RUNNING" if snapshot.next else "COMPLETED",
        state=snapshot.values,
    )


@router.post("", status_code=status.HTTP_201_CREATED)
def create_execution(
    body: ExecutionRequest,
    runner: Runner,
    reporter: Reporter,
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
        graph = runner.graph_for_workflow(workflow)
    except CompileError as e:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, str(e)) from e
    dsl = workflow.model_dump(mode="json", by_alias=True)
    initial = {"task": body.task, "workflow": dsl, "revisions": {}}

    execution_id = body.execution_id or str(uuid.uuid4())
    if runner.exists(execution_id):
        raise HTTPException(status.HTTP_409_CONFLICT, f"Execution '{execution_id}' already exists")

    if body.background:
        background.add_task(_run_logged, graph, initial, execution_id, reporter)
        response.status_code = status.HTTP_202_ACCEPTED
        return ExecutionResponse(execution_id=execution_id, status="RUNNING")

    # Runs synchronously until it completes or pauses for approval.
    run_graph(graph, initial, _config(execution_id), reporter)
    return _describe(graph, execution_id)


@router.get("/{execution_id}")
def get_execution(execution_id: str, runner: Runner) -> ExecutionResponse:
    return _describe(_graph_for(runner, execution_id), execution_id)


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
    with _resuming_lock:
        current = _describe(graph, execution_id)
        if execution_id in _resuming or current.status != "WAITING_APPROVAL":
            raise HTTPException(status.HTTP_409_CONFLICT, "Execution is not waiting for approval")
        if current.waiting_approval_id != body.approval_id:
            raise HTTPException(
                status.HTTP_409_CONFLICT,
                f"Execution is waiting for approval {current.waiting_approval_id}",
            )
        _resuming.add(execution_id)

    background.add_task(_resume, graph, execution_id, body.decision, reporter)
    return ExecutionResponse(execution_id=execution_id, status="RESUMING")


def _resume(
    graph: CompiledStateGraph, execution_id: str, decision: str, reporter: EventReporter
) -> None:
    try:
        _run_logged(graph, Command(resume={"decision": decision}), execution_id, reporter)
    finally:
        with _resuming_lock:
            _resuming.discard(execution_id)


def _run_logged(
    graph: CompiledStateGraph, graph_input: Any, execution_id: str, reporter: EventReporter
) -> None:
    # Background runs have no caller to raise to; the failure was reported as an event.
    try:
        run_graph(graph, graph_input, _config(execution_id), reporter)
    except Exception:
        log.exception("Execution %s failed", execution_id)
