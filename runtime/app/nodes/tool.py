"""Governed HTTP tool as a LangGraph sub-flow.

    <name>            ask AgentGate (once)
      ├─ ALLOWED            → <name>.execute
      ├─ APPROVAL_REQUIRED  → <name>.approval  (interrupt until resumed)
      │                          ├─ APPROVED → <name>.execute
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

from app.tools.http import HttpTool, ToolResult


def add_http_tool(
    graph: StateGraph,
    name: str,
    tool: HttpTool,
    build_payload: Callable[[dict], dict[str, Any]],
    next_node: str | list[str],
    output_key: str | None = None,
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

    def gate(state: dict, config: RunnableConfig) -> Command:
        # The LangGraph thread id is the execution id AgentGate links approvals to.
        execution_id = config.get("configurable", {}).get("thread_id")
        result = tool.authorize(execution_id)
        update = {"pending_tool": result.model_dump()}
        if result.status == "ALLOWED":
            return Command(update=update, goto=execute_node)
        if result.status == "APPROVAL_REQUIRED":
            return Command(update=update, goto=approval_node)
        return Command(update=finish(result), goto=next_nodes)

    def await_approval(state: dict) -> Command:
        pending = state["pending_tool"]
        decision = interrupt({"tool": name, "approval_id": pending["approval_id"]})
        if decision.get("decision") == "APPROVED":
            return Command(goto=execute_node)
        return Command(
            update=finish(ToolResult(**{**pending, "status": "REJECTED"})), goto=next_nodes
        )

    def execute(state: dict) -> dict:
        authorized = ToolResult(**state["pending_tool"])
        return finish(tool.execute(build_payload(state), authorized))

    graph.add_node(name, gate, destinations=(execute_node, approval_node, *next_nodes))
    graph.add_node(approval_node, await_approval, destinations=(execute_node, *next_nodes))
    graph.add_node(execute_node, execute)
    for target in next_nodes:
        graph.add_edge(execute_node, target)
