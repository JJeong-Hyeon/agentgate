from typing import Literal

import httpx
from pydantic import BaseModel, ConfigDict, Field


class AgentGateError(Exception):
    pass


class Decision(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    status: Literal["ALLOWED", "APPROVAL_REQUIRED", "BLOCKED"]
    risk_level: str = Field(alias="riskLevel")
    approval_id: int | None = Field(default=None, alias="approvalId")


class AgentGateClient:
    """Client for AgentGate's action evaluation API (POST /api/v1/actions)."""

    def __init__(
        self,
        base_url: str,
        agent_id: str,
        api_key: str,
        transport: httpx.BaseTransport | None = None,
        timeout: float = 10.0,
    ):
        self._agent_id = agent_id
        self._client = httpx.Client(
            base_url=base_url,
            headers={"X-API-Key": api_key},
            transport=transport,
            timeout=timeout,
        )

    def evaluate(
        self,
        action: str,
        target: str | None = None,
        labels: list[str] | None = None,
        execution_id: str | None = None,
        require_approval: bool = False,
        reason: str | None = None,
    ) -> Decision:
        body = {
            "agentId": self._agent_id,
            "action": action,
            "target": target,
            "labels": labels or [],
            "executionId": execution_id,
        }
        if require_approval:
            body["requireApproval"] = True
        if reason:
            body["reason"] = reason
        try:
            response = self._client.post("/api/v1/actions", json=body)
        except httpx.HTTPError as e:
            raise AgentGateError(f"AgentGate unreachable: {e}") from e
        if response.status_code != 200:
            raise AgentGateError(f"AgentGate returned {response.status_code}: {response.text}")
        return Decision.model_validate(response.json())
