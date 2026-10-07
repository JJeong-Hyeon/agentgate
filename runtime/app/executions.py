import uuid
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, HTTPException, Request, status
from langgraph.graph.state import CompiledStateGraph
from pydantic import BaseModel, Field

router = APIRouter(prefix="/runtime/executions", tags=["executions"])


class ExecutionRequest(BaseModel):
    task: str = Field(min_length=1)


class ExecutionResponse(BaseModel):
    execution_id: str
    status: Literal["RUNNING", "COMPLETED"]
    state: dict


def get_graph(request: Request) -> CompiledStateGraph:
    return request.app.state.graph


Graph = Annotated[CompiledStateGraph, Depends(get_graph)]


def _config(execution_id: str) -> dict:
    return {"configurable": {"thread_id": execution_id}}


@router.post("", status_code=status.HTTP_201_CREATED)
def create_execution(body: ExecutionRequest, graph: Graph) -> ExecutionResponse:
    # Runs synchronously for now; background execution comes with execution events.
    execution_id = str(uuid.uuid4())
    state = graph.invoke({"task": body.task, "revisions": 0}, _config(execution_id))
    return ExecutionResponse(execution_id=execution_id, status="COMPLETED", state=state)


@router.get("/{execution_id}")
def get_execution(execution_id: str, graph: Graph) -> ExecutionResponse:
    snapshot = graph.get_state(_config(execution_id))
    if not snapshot.values:
        raise HTTPException(status.HTTP_404_NOT_FOUND, f"Execution '{execution_id}' not found")
    return ExecutionResponse(
        execution_id=execution_id,
        status="RUNNING" if snapshot.next else "COMPLETED",
        state=snapshot.values,
    )
