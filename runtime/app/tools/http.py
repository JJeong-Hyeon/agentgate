from typing import Any, Literal

import httpx
from pydantic import BaseModel

from app.governance.agentgate_client import AgentGateClient, AgentGateError

_MAX_BODY_CHARS = 2000


class HttpToolSpec(BaseModel):
    name: str
    action: str
    url: str
    method: Literal["GET", "POST", "PUT", "PATCH", "DELETE"] = "POST"
    labels: list[str] = []


class ToolResult(BaseModel):
    tool: str
    # ALLOWED / APPROVAL_REQUIRED are intermediate (gate passed, waiting); the rest are final.
    status: Literal["ALLOWED", "APPROVAL_REQUIRED", "EXECUTED", "BLOCKED", "REJECTED", "FAILED"]
    risk_level: str | None = None
    approval_id: int | None = None
    response_status: int | None = None
    response_body: str | None = None
    error: str | None = None


class HttpTool:
    """HTTP call guarded by AgentGate: the request is sent only when AgentGate allows it.

    authorize() and execute() are separate so a graph can pause for human approval between
    them without asking AgentGate twice. Any failure to get a decision is fail-closed.
    """

    def __init__(
        self,
        spec: HttpToolSpec,
        gate: AgentGateClient,
        transport: httpx.BaseTransport | None = None,
        timeout: float = 30.0,
    ):
        self.spec = spec
        self._gate = gate
        self._client = httpx.Client(transport=transport, timeout=timeout)

    def authorize(self, execution_id: str | None = None) -> ToolResult:
        spec = self.spec
        try:
            decision = self._gate.evaluate(
                spec.action, target=spec.url, labels=spec.labels, execution_id=execution_id
            )
        except AgentGateError as e:
            return ToolResult(tool=spec.name, status="FAILED", error=str(e))
        return ToolResult(
            tool=spec.name,
            status=decision.status,
            risk_level=decision.risk_level,
            approval_id=decision.approval_id,
        )

    def execute(self, payload: dict[str, Any] | None, authorized: ToolResult) -> ToolResult:
        spec = self.spec
        base = authorized.model_dump(include={"tool", "risk_level", "approval_id"})
        try:
            response = self._client.request(spec.method, spec.url, json=payload)
        except httpx.HTTPError as e:
            return ToolResult(**base, status="FAILED", error=str(e))
        return ToolResult(
            **base,
            status="EXECUTED" if response.is_success else "FAILED",
            response_status=response.status_code,
            response_body=response.text[:_MAX_BODY_CHARS],
        )

    def run(
        self, payload: dict[str, Any] | None = None, execution_id: str | None = None
    ) -> ToolResult:
        """Authorize and, only if ALLOWED, execute. Does not wait for approval."""
        authorized = self.authorize(execution_id)
        if authorized.status != "ALLOWED":
            return authorized
        return self.execute(payload, authorized)
