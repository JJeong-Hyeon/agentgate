"""Delegation as a governed tool: a supervisor agent hands a task to another agent.

AgentGate evaluates the delegation (action AGENT:<agentId>) for the supervisor; the delegate
then runs as a LangGraph subgraph under its own definition, permissions and name. An approval
the delegate waits for pauses the whole execution (the interrupt propagates), and resuming
continues inside the delegate where it stopped.
"""

from collections.abc import Callable
from typing import Any

from langgraph.errors import GraphInterrupt

from app.governance.agentgate_client import AgentGateClient
from app.tools.base import GovernedTool, ToolResult

MAX_ANSWER_CHARS = 8000

TASK_PARAMETERS = {
    "type": "object",
    "properties": {
        "task": {
            "type": "string",
            "description": "What the agent should do, with all the context it needs.",
        }
    },
    "required": ["task"],
}


class DelegateTool(GovernedTool):
    def __init__(self, name: str, agent_id: str, run: Callable[[str], str], gate: AgentGateClient):
        super().__init__(name, f"AGENT:{agent_id}", f"agent://{agent_id}", [], gate)
        self.agent_id = agent_id
        self._run = run

    def execute(self, payload: dict[str, Any] | None, authorized: ToolResult) -> ToolResult:
        task = str((payload or {}).get("task") or "").strip()
        if not task:
            return ToolResult(**self._base(authorized), status="FAILED", error="task is required")
        try:
            answer = self._run(task)
        except GraphInterrupt:
            # The delegate waits for an approval: pause the whole execution.
            raise
        except Exception as e:  # noqa: BLE001 - a failing delegate fails the call, not the run
            error = f"agent '{self.agent_id}' failed: {e}"
            return ToolResult(**self._base(authorized), status="FAILED", error=error)
        return ToolResult(
            **self._base(authorized), status="EXECUTED", response_body=answer[:MAX_ANSWER_CHARS]
        )
