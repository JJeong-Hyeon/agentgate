from typing import Any, Literal

import httpx
from pydantic import BaseModel

from app.governance.agentgate_client import AgentGateClient
from app.tools.base import MAX_OUTPUT_CHARS, GovernedTool, ToolResult

__all__ = ["HttpTool", "HttpToolSpec", "ToolResult"]


class HttpToolSpec(BaseModel):
    name: str
    action: str
    url: str
    method: Literal["GET", "POST", "PUT", "PATCH", "DELETE"] = "POST"
    labels: list[str] = []


class HttpTool(GovernedTool):
    """HTTP call guarded by AgentGate: the request is sent only when AgentGate allows it."""

    def __init__(
        self,
        spec: HttpToolSpec,
        gate: AgentGateClient,
        transport: httpx.BaseTransport | None = None,
        timeout: float = 30.0,
    ):
        super().__init__(spec.name, spec.action, spec.url, spec.labels, gate)
        self.spec = spec
        self._client = httpx.Client(transport=transport, timeout=timeout)

    def execute(self, payload: dict[str, Any] | None, authorized: ToolResult) -> ToolResult:
        try:
            response = self._client.request(self.spec.method, self.spec.url, json=payload)
        except httpx.HTTPError as e:
            return ToolResult(**self._base(authorized), status="FAILED", error=str(e))
        return ToolResult(
            **self._base(authorized),
            status="EXECUTED" if response.is_success else "FAILED",
            response_status=response.status_code,
            response_body=response.text[:MAX_OUTPUT_CHARS],
        )
