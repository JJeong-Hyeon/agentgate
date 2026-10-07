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
    status: Literal["EXECUTED", "APPROVAL_REQUIRED", "BLOCKED", "FAILED"]
    risk_level: str | None = None
    approval_id: int | None = None
    response_status: int | None = None
    response_body: str | None = None
    error: str | None = None


class HttpTool:
    """HTTP call guarded by AgentGate: the request is sent only when AgentGate allows it.

    Any failure to get a decision is fail-closed — the request is not sent.
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

    def run(
        self, payload: dict[str, Any] | None = None, execution_id: str | None = None
    ) -> ToolResult:
        spec = self.spec
        try:
            decision = self._gate.evaluate(
                spec.action, target=spec.url, labels=spec.labels, execution_id=execution_id
            )
        except AgentGateError as e:
            return ToolResult(tool=spec.name, status="FAILED", error=str(e))

        if decision.status != "ALLOWED":
            return ToolResult(
                tool=spec.name,
                status=decision.status,
                risk_level=decision.risk_level,
                approval_id=decision.approval_id,
            )

        try:
            response = self._client.request(spec.method, spec.url, json=payload)
        except httpx.HTTPError as e:
            return ToolResult(
                tool=spec.name, status="FAILED", risk_level=decision.risk_level, error=str(e)
            )
        return ToolResult(
            tool=spec.name,
            status="EXECUTED" if response.is_success else "FAILED",
            risk_level=decision.risk_level,
            response_status=response.status_code,
            response_body=response.text[:_MAX_BODY_CHARS],
        )
