"""Governed HTTP tool as a LangGraph sub-flow.

    <name>            ask AgentGate (once)
      ├─ ALLOWED            → <name>_execute
      ├─ APPROVAL_REQUIRED  → <name>_approval  (interrupt until resumed)
      │                          ├─ APPROVED → <name>_execute
      │                          └─ REJECTED → record REJECTED
      └─ BLOCKED / FAILED   → record result

The gate result is kept in state before interrupting, because LangGraph re-runs an
interrupted node from the top on resume; AgentGate must not see the request twice.
"""

from collections.abc import Callable
from typing import Any

from langchain_core.runnables import RunnableConfig
from langgraph.graph import StateGraph
from langgraph.types import Command, interrupt

from app.graph.state import ResearchState
from app.tools.http import HttpTool, ToolResult


def add_http_tool(
    graph: StateGraph,
    name: str,
    tool: HttpTool,
    build_payload: Callable[[ResearchState], dict[str, Any]],
    next_node: str,
) -> None:
    approval_node = f"{name}_approval"
    execute_node = f"{name}_execute"

    def gate(state: ResearchState, config: RunnableConfig) -> Command:
        # The LangGraph thread id is the execution id AgentGate links approvals to.
        execution_id = config.get("configurable", {}).get("thread_id")
        result = tool.authorize(execution_id)
        update = {"pending_tool": result.model_dump()}
        if result.status == "ALLOWED":
            return Command(update=update, goto=execute_node)
        if result.status == "APPROVAL_REQUIRED":
            return Command(update=update, goto=approval_node)
        return Command(
            update={"pending_tool": None, "tool_results": [result.model_dump()]}, goto=next_node
        )

    def await_approval(state: ResearchState) -> Command:
        pending = state["pending_tool"]
        decision = interrupt({"tool": name, "approval_id": pending["approval_id"]})
        if decision.get("decision") == "APPROVED":
            return Command(goto=execute_node)
        rejected = ToolResult(**{**pending, "status": "REJECTED"})
        return Command(
            update={"pending_tool": None, "tool_results": [rejected.model_dump()]}, goto=next_node
        )

    def execute(state: ResearchState) -> ResearchState:
        authorized = ToolResult(**state["pending_tool"])
        result = tool.execute(build_payload(state), authorized)
        return {"pending_tool": None, "tool_results": [result.model_dump()]}

    graph.add_node(name, gate, destinations=(execute_node, approval_node, next_node))
    graph.add_node(approval_node, await_approval, destinations=(execute_node, next_node))
    graph.add_node(execute_node, execute)
    graph.add_edge(execute_node, next_node)
