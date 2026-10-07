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

    client(gate).evaluate("SEND_REPORT", target="http://hook", labels=["PII"])

    request = gate.requests[0]
    assert request.url == "http://agentgate:8080/api/v1/actions"
    assert request.headers["X-API-Key"] == "secret"
    assert gate.bodies[0] == {
        "agentId": "runtime-agent",
        "action": "SEND_REPORT",
        "target": "http://hook",
        "labels": ["PII"],
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
