"""What every tool shares: an AgentGate check before it may run, and one result format."""

from abc import ABC, abstractmethod
from typing import Any, Literal

from pydantic import BaseModel

from app.governance.agentgate_client import AgentGateClient, AgentGateError

MAX_OUTPUT_CHARS = 2000


class ToolResult(BaseModel):
    tool: str
    # ALLOWED / APPROVAL_REQUIRED are intermediate (gate passed, waiting); the rest are final.
    status: Literal["ALLOWED", "APPROVAL_REQUIRED", "EXECUTED", "BLOCKED", "REJECTED", "FAILED"]
    risk_level: str | None = None
    approval_id: int | None = None
    response_status: int | None = None
    response_body: str | None = None
    error: str | None = None


class GovernedTool(ABC):
    """A tool that runs only when AgentGate allows it.

    authorize() and execute() are separate so a graph can pause for human approval between
    them without asking AgentGate twice. Any failure to get a decision is fail-closed.
    """

    def __init__(
        self, name: str, action: str, target: str, labels: list[str], gate: AgentGateClient
    ):
        self.name = name
        self.action = action
        self.target = target
        self.labels = labels
        self._gate = gate

    def authorize(self, execution_id: str | None = None) -> ToolResult:
        try:
            decision = self._gate.evaluate(
                self.action, target=self.target, labels=self.labels, execution_id=execution_id
            )
        except AgentGateError as e:
            return ToolResult(tool=self.name, status="FAILED", error=str(e))
        return ToolResult(
            tool=self.name,
            status=decision.status,
            risk_level=decision.risk_level,
            approval_id=decision.approval_id,
        )

    @abstractmethod
    def execute(self, payload: dict[str, Any] | None, authorized: ToolResult) -> ToolResult:
        """Run the tool; only called after authorize() allowed it (directly or via approval)."""

    def run(
        self, payload: dict[str, Any] | None = None, execution_id: str | None = None
    ) -> ToolResult:
        """Authorize and, only if ALLOWED, execute. Does not wait for approval."""
        authorized = self.authorize(execution_id)
        if authorized.status != "ALLOWED":
            return authorized
        return self.execute(payload, authorized)

    def _base(self, authorized: ToolResult) -> dict[str, Any]:
        return authorized.model_dump(include={"tool", "risk_level", "approval_id"})
