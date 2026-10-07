"""Explicit human approval as a LangGraph sub-flow.

    <name>            ask AgentGate for an approval (requireApproval)
      ├─ APPROVAL_REQUIRED → <name>.approval  (interrupt until resumed)
      │                         ├─ APPROVED → next nodes
      │                         └─ REJECTED → end of execution
      ├─ ALLOWED           → next nodes
      └─ BLOCKED / FAILED  → end of execution

The decision is written to state[<name>] and recorded in tool_results.
"""

from collections.abc import Callable

from langchain_core.runnables import RunnableConfig
from langgraph.graph import END, StateGraph
from langgraph.types import Command, interrupt

from app.governance.agentgate_client import AgentGateClient, AgentGateError


def add_approval(
    graph: StateGraph,
    name: str,
    gate: AgentGateClient,
    action: str,
    labels: list[str],
    build_reason: Callable[[dict], str | None],
    next_nodes: list[str],
) -> None:
    wait_node = f"{name}.approval"

    def record(status: str, approval_id: int | None = None, error: str | None = None) -> dict:
        result = {"tool": name, "status": status, "approval_id": approval_id, "error": error}
        return {name: status, "pending_tool": None, "tool_results": [result]}

    def request(state: dict, config: RunnableConfig) -> Command:
        execution_id = config.get("configurable", {}).get("thread_id")
        try:
            decision = gate.evaluate(
                action,
                labels=labels,
                execution_id=execution_id,
                require_approval=True,
                reason=build_reason(state),
            )
        except AgentGateError as e:
            # Fail closed: without a decision the workflow does not continue.
            return Command(update=record("FAILED", error=str(e)), goto=END)
        if decision.status == "APPROVAL_REQUIRED":
            pending = {"tool": name, "approval_id": decision.approval_id}
            return Command(update={"pending_tool": pending}, goto=wait_node)
        if decision.status == "ALLOWED":
            return Command(update=record("APPROVED"), goto=next_nodes)
        return Command(update=record("BLOCKED"), goto=END)

    def wait(state: dict) -> Command:
        approval_id = state["pending_tool"]["approval_id"]
        decision = interrupt({"tool": name, "approval_id": approval_id})
        if decision.get("decision") == "APPROVED":
            return Command(update=record("APPROVED", approval_id), goto=next_nodes)
        return Command(update=record("REJECTED", approval_id), goto=END)

    graph.add_node(name, request, destinations=(wait_node, *next_nodes, END))
    graph.add_node(wait_node, wait, destinations=(*next_nodes, END))
