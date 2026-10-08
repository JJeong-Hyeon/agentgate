"""Governed tool (HTTP / MCP) as a LangGraph sub-flow.

    <name>            ask AgentGate (once)
      ├─ ALLOWED            → <name>.execute
      ├─ APPROVAL_REQUIRED  → <name>.approval  (interrupt until resumed)
      │                          ├─ APPROVED → <name>.execute
      │                          └─ REJECTED → denied
      └─ BLOCKED / FAILED   → denied

A denied call stops the execution (state["stopped"], then END) unless `on_denied` is
CONTINUE, in which case its result is recorded and the next nodes run.

The gate result is kept in state before interrupting, because LangGraph re-runs an
interrupted node from the top on resume; AgentGate must not see the request twice.
"""

from collections.abc import Callable
from typing import Any

from langchain_core.runnables import RunnableConfig
from langgraph.graph import END, StateGraph
from langgraph.types import Command, interrupt

from app.tools.base import GovernedTool, ToolResult


def add_tool(
    graph: StateGraph,
    name: str,
    tool: GovernedTool,
    build_payload: Callable[[dict], dict[str, Any]],
    next_node: str | list[str],
    output_key: str | None = None,
    on_denied: str = "STOP",
) -> None:
    """Add the gate/approval/execute sub-flow for `tool` under node name `name`.

    Sub-node names use '.', which DSL node ids cannot contain, so they never collide.
    With `output_key`, the final result is also written to state[output_key] (the response
    body when executed, otherwise the status) so later prompts can use it.
    """
    approval_node = f"{name}.approval"
    execute_node = f"{name}.execute"
    next_nodes = [next_node] if isinstance(next_node, str) else list(next_node)

    def finish(result: ToolResult) -> dict:
        update = {"pending_tool": None, "tool_results": [result.model_dump()]}
        if output_key:
            executed = result.status == "EXECUTED"
            update[output_key] = result.response_body if executed else result.status
        return update

    def denied(result: ToolResult) -> Command:
        update = finish(result)
        if on_denied == "CONTINUE":
            return Command(update=update, goto=next_nodes)
        return Command(update={**update, "stopped": stop_record(name, result)}, goto=END)

    def gate(state: dict, config: RunnableConfig) -> Command:
        # The LangGraph thread id is the execution id AgentGate links approvals to.
        execution_id = config.get("configurable", {}).get("thread_id")
        result = tool.authorize(execution_id)
        update = {"pending_tool": result.model_dump()}
        if result.status == "ALLOWED":
            return Command(update=update, goto=execute_node)
        if result.status == "APPROVAL_REQUIRED":
            return Command(update=update, goto=approval_node)
        return denied(result)

    def await_approval(state: dict) -> Command:
        pending = state["pending_tool"]
        decision = interrupt({"tool": name, "approval_id": pending["approval_id"]})
        if decision.get("decision") == "APPROVED":
            return Command(goto=execute_node)
        return denied(ToolResult(**{**pending, "status": "REJECTED"}))

    def execute(state: dict) -> dict:
        authorized = ToolResult(**state["pending_tool"])
        return finish(tool.execute(build_payload(state), authorized))

    graph.add_node(name, gate, destinations=(execute_node, approval_node, *next_nodes, END))
    graph.add_node(approval_node, await_approval, destinations=(execute_node, *next_nodes, END))
    graph.add_node(execute_node, execute)
    for target in next_nodes:
        graph.add_edge(execute_node, target)


_STOP_REASONS = {
    "REJECTED": "rejected by an approver",
    "BLOCKED": "blocked by AgentGate",
    "FAILED": "could not be authorized",
}


def stop_record(node: str, result: ToolResult) -> dict:
    """Why the execution stopped: which node, what happened, and a readable reason."""
    reason = f"{node}: {_STOP_REASONS.get(result.status, result.status.lower())}"
    if result.status == "BLOCKED" and result.basis:
        # Why AgentGate blocked it; for a rejection the human decided, whatever the basis.
        reason += f" ({result.basis})"
    if result.error:
        reason += f": {result.error}"
    return {"node": node, "status": result.status, "reason": reason}
