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
    # Which rule decided (POLICY, TOOL_NOT_GRANTED, ...); absent from older AgentGate versions.
    basis: str | None = None


class AgentGateClient:
    """Client for AgentGate's action evaluation API (POST /api/v1/actions).

    With a runtime token the runtime is a trusted caller and evaluates on behalf of whichever
    agent it is running (evaluate(agent_id=...)); otherwise it acts as `agent_id` with its API key.
    """

    def __init__(
        self,
        base_url: str,
        agent_id: str,
        api_key: str = "",
        transport: httpx.BaseTransport | None = None,
        timeout: float = 10.0,
        runtime_token: str = "",
    ):
        self._agent_id = agent_id
        headers = {"X-Runtime-Token": runtime_token} if runtime_token else {"X-API-Key": api_key}
        self._client = httpx.Client(
            base_url=base_url, headers=headers, transport=transport, timeout=timeout
        )

    def evaluate(
        self,
        action: str,
        target: str | None = None,
        labels: list[str] | None = None,
        execution_id: str | None = None,
        require_approval: bool = False,
        reason: str | None = None,
        agent_id: str | None = None,
        agent_version: int | None = None,
        delegated_by: str | None = None,
    ) -> Decision:
        """`agent_id` defaults to the client's own; `agent_version` applies that agent
        definition's tool permissions."""
        body = {
            "agentId": agent_id or self._agent_id,
            "action": action,
            "target": target,
            "labels": labels or [],
            "executionId": execution_id,
        }
        if require_approval:
            body["requireApproval"] = True
        if reason:
            body["reason"] = reason
        if agent_version is not None:
            body["agentVersion"] = agent_version
        if delegated_by:
            body["delegatedBy"] = delegated_by
        try:
            response = self._client.post("/api/v1/actions", json=body)
        except httpx.HTTPError as e:
            raise AgentGateError(f"AgentGate unreachable: {e}") from e
        if response.status_code != 200:
            raise AgentGateError(f"AgentGate returned {response.status_code}: {response.text}")
        return Decision.model_validate(response.json())
