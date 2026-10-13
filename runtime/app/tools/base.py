"""What every tool shares: an AgentGate check before it may run, and one result format."""

import time
from abc import ABC, abstractmethod
from typing import Any, Literal

from opentelemetry.trace import Status, StatusCode
from pydantic import BaseModel, Field

from app.governance.agentgate_client import AgentGateClient, AgentGateError
from app.governance.payload import payload_hash, payload_preview
from app.telemetry import current_step, set_attributes, set_content, tracer

MAX_OUTPUT_CHARS = 2000


class PayloadMismatch(Exception):
    """What is about to run is not what the approver approved; the execution fails instead."""


class ToolResult(BaseModel):
    tool: str
    # ALLOWED / APPROVAL_REQUIRED are intermediate (gate passed, waiting); the rest are final.
    status: Literal["ALLOWED", "APPROVAL_REQUIRED", "EXECUTED", "BLOCKED", "REJECTED", "FAILED"]
    risk_level: str | None = None
    approval_id: int | None = None
    # Which AgentGate rule decided (POLICY, TOOL_NOT_GRANTED, ...), when it says.
    basis: str | None = None
    response_status: int | None = None
    response_body: str | None = None
    error: str | None = None
    # How long running the tool took (not the governance check or an approval wait).
    duration_ms: int | None = None
    # Set just before running, so the receiving system can tell a repeat of this call (a retry, a
    # recovery) from a new one; never saved with the result.
    idempotency_key: str | None = Field(default=None, exclude=True)


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

    def authorize(
        self,
        execution_id: str | None = None,
        agent_id: str | None = None,
        agent_version: int | None = None,
        reason: str | None = None,
        delegated_by: str | None = None,
        payload: dict[str, Any] | None = None,
    ) -> ToolResult:
        """Ask AgentGate; with `agent_id` / `agent_version` it is asked on behalf of that agent,
        whose definition's tool permissions then apply. With the `payload` about to be sent, its
        hash is recorded with any approval, to be checked by payload_matches() before running."""
        with tracer().start_as_current_span(f"governance {self.action}") as span:
            set_attributes(
                span,
                {
                    "agentgate.action": self.action,
                    "agentgate.target": self.target,
                    "agentgate.labels": list(self.labels) or None,
                    "agentgate.agent.id": agent_id,
                    "agentgate.agent.version": agent_version,
                    "agentgate.delegated_by": delegated_by or None,
                    "agentgate.step": current_step(),
                },
            )
            try:
                decision = self._gate.evaluate(
                    self.action,
                    target=self.target,
                    labels=self.labels,
                    execution_id=execution_id,
                    reason=reason,
                    agent_id=agent_id,
                    agent_version=agent_version,
                    delegated_by=delegated_by,
                    payload_hash=self.payload_hash(payload) if payload is not None else None,
                    payload_preview=payload_preview(payload) if payload is not None else None,
                )
            except AgentGateError as e:
                span.set_status(Status(StatusCode.ERROR, str(e)))
                span.set_attribute("agentgate.decision.status", "FAILED")
                return ToolResult(tool=self.name, status="FAILED", error=str(e))
            set_attributes(
                span,
                {
                    "agentgate.decision.status": decision.status,
                    "agentgate.decision.risk_level": decision.risk_level,
                    "agentgate.decision.basis": decision.basis,
                    "agentgate.approval.id": decision.approval_id,
                },
            )
        return ToolResult(
            tool=self.name,
            status=decision.status,
            risk_level=decision.risk_level,
            approval_id=decision.approval_id,
            basis=decision.basis,
        )

    def payload_hash(self, payload: dict[str, Any] | None) -> str:
        return payload_hash(self.action, self.target, payload)

    def check_payload(self, payload: dict[str, Any] | None, approved_hash: str | None) -> None:
        """Raises PayloadMismatch unless `payload` is what AgentGate recorded for the approval.
        No recorded hash (an approval from before hashes existed) is not checked."""
        if approved_hash is not None and approved_hash != self.payload_hash(payload):
            raise PayloadMismatch(
                f"{self.name}: the payload to run differs from the one that was approved"
            )

    @abstractmethod
    def execute(self, payload: dict[str, Any] | None, authorized: ToolResult) -> ToolResult:
        """Run the tool; only called after authorize() allowed it (directly or via approval)."""

    def execute_timed(self, payload: dict[str, Any] | None, authorized: ToolResult) -> ToolResult:
        """execute(), with how long it took recorded in the result (and traced)."""
        with tracer().start_as_current_span(f"tool {self.name}") as span:
            set_attributes(
                span,
                {
                    "agentgate.tool": self.name,
                    "agentgate.action": self.action,
                    "agentgate.target": self.target,
                    "agentgate.approval.id": authorized.approval_id,
                    "agentgate.step": current_step(),
                },
            )
            set_content(span, "langfuse.observation.input", payload)
            started = time.perf_counter()
            result = self.execute(payload, authorized)
            elapsed = round((time.perf_counter() - started) * 1000)
            span.set_attribute("agentgate.tool.status", result.status)
            if result.status == "FAILED":
                span.set_status(Status(StatusCode.ERROR, result.error or "tool failed"))
            set_content(span, "langfuse.observation.output", result.response_body or result.error)
        return result.model_copy(update={"duration_ms": elapsed})

    def run(
        self, payload: dict[str, Any] | None = None, execution_id: str | None = None
    ) -> ToolResult:
        """Authorize and, only if ALLOWED, execute. Does not wait for approval."""
        authorized = self.authorize(execution_id)
        if authorized.status != "ALLOWED":
            return authorized
        return self.execute_timed(payload, authorized)

    def _base(self, authorized: ToolResult) -> dict[str, Any]:
        return authorized.model_dump(include={"tool", "risk_level", "approval_id", "basis"})
