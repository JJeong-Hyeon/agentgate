import httpx
import pytest

from app.governance.agentgate_client import AgentGateClient, AgentGateError
from tests.fakes import FakeAgentGate


def client(handler) -> AgentGateClient:
    return AgentGateClient(
        "http://agentgate:8080", "runtime-agent", "secret", httpx.MockTransport(handler)
    )


def test_sends_action_with_api_key():
    gate = FakeAgentGate()

    client(gate).evaluate("SEND_REPORT", target="http://hook", labels=["PII"], execution_id="e-1")

    request = gate.requests[0]
    assert request.url == "http://agentgate:8080/api/v1/actions"
    assert request.headers["X-API-Key"] == "secret"
    assert gate.bodies[0] == {
        "agentId": "runtime-agent",
        "action": "SEND_REPORT",
        "target": "http://hook",
        "labels": ["PII"],
        "executionId": "e-1",
    }


def test_parses_decision():
    gate = FakeAgentGate(status="APPROVAL_REQUIRED", risk_level="HIGH", approval_id=12)

    decision = client(gate).evaluate("SEND_REPORT")

    assert decision.status == "APPROVAL_REQUIRED"
    assert decision.risk_level == "HIGH"
    assert decision.approval_id == 12


def test_error_response_raises():
    with pytest.raises(AgentGateError, match="401"):
        client(FakeAgentGate(http_status=401)).evaluate("SEND_REPORT")


def test_unreachable_raises():
    def refuse(request):
        raise httpx.ConnectError("connection refused")

    with pytest.raises(AgentGateError, match="unreachable"):
        client(refuse).evaluate("SEND_REPORT")


def test_runtime_token_evaluates_on_behalf_of_an_agent_version():
    gate = FakeAgentGate()
    runtime = AgentGateClient(
        "http://agentgate:8080",
        "runtime-agent",
        transport=httpx.MockTransport(gate),
        runtime_token="runtime-secret",
    )

    runtime.evaluate("MCP:notes:save_note", agent_id="note-agent", agent_version=3)

    request = gate.requests[0]
    assert request.headers["X-Runtime-Token"] == "runtime-secret"
    assert "X-API-Key" not in request.headers
    assert gate.bodies[0]["agentId"] == "note-agent"
    assert gate.bodies[0]["agentVersion"] == 3


def test_parses_decision_basis():
    gate = FakeAgentGate(status="BLOCKED", risk_level="BLOCKED", basis="TOOL_NOT_GRANTED")

    assert client(gate).evaluate("MCP:files:read").basis == "TOOL_NOT_GRANTED"
