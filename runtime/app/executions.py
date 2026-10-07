import secrets
import threading
import uuid
from typing import Annotated, Literal

from fastapi import APIRouter, BackgroundTasks, Depends, Header, HTTPException, Request, status
from langgraph.graph.state import CompiledStateGraph
from langgraph.types import Command
from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from app.config import Settings, get_settings

router = APIRouter(prefix="/runtime/executions", tags=["executions"])


class ExecutionRequest(BaseModel):
    task: str = Field(min_length=1)


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


def get_graph(request: Request) -> CompiledStateGraph:
    return request.app.state.graph


Graph = Annotated[CompiledStateGraph, Depends(get_graph)]

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
def create_execution(body: ExecutionRequest, graph: Graph) -> ExecutionResponse:
    # Runs synchronously until it completes or pauses for approval.
    execution_id = str(uuid.uuid4())
    graph.invoke({"task": body.task, "revisions": 0}, _config(execution_id))
    return _describe(graph, execution_id)


@router.get("/{execution_id}")
def get_execution(execution_id: str, graph: Graph) -> ExecutionResponse:
    return _describe(graph, execution_id)


@router.post(
    "/{execution_id}/resume",
    status_code=status.HTTP_202_ACCEPTED,
    dependencies=[Depends(require_runtime_token)],
)
def resume_execution(
    execution_id: str, body: ResumeRequest, graph: Graph, background: BackgroundTasks
) -> ExecutionResponse:
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

    background.add_task(_resume, graph, execution_id, body.decision)
    return ExecutionResponse(execution_id=execution_id, status="RESUMING")


def _resume(graph: CompiledStateGraph, execution_id: str, decision: str) -> None:
    try:
        graph.invoke(Command(resume={"decision": decision}), _config(execution_id))
    finally:
        with _resuming_lock:
            _resuming.discard(execution_id)
