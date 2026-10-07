import httpx

from app.governance.agentgate_client import AgentGateClient
from app.tools.http import HttpTool, HttpToolSpec
from tests.fakes import FakeAgentGate, FakeTarget

SPEC = HttpToolSpec(name="report", action="SEND_REPORT", url="http://hook/report", labels=["PII"])


def tool(gate, target=None) -> HttpTool:
    client = AgentGateClient("http://agentgate", "runtime-agent", "k", httpx.MockTransport(gate))
    return HttpTool(SPEC, client, httpx.MockTransport(target or FakeTarget()))


def test_allowed_sends_request():
    target = FakeTarget()

    result = tool(FakeAgentGate("ALLOWED", "LOW"), target).run({"msg": "hi"})

    assert result.status == "EXECUTED"
    assert result.risk_level == "LOW"
    assert result.response_status == 200
    assert target.requests[0].method == "POST"
    assert target.requests[0].content == b'{"msg":"hi"}'


def test_gate_receives_tool_action_target_and_labels():
    gate = FakeAgentGate()

    tool(gate).run()

    assert gate.bodies[0]["action"] == "SEND_REPORT"
    assert gate.bodies[0]["target"] == "http://hook/report"
    assert gate.bodies[0]["labels"] == ["PII"]


def test_approval_required_does_not_send():
    target = FakeTarget()

    result = tool(FakeAgentGate("APPROVAL_REQUIRED", "HIGH", 7), target).run()

    assert result.status == "APPROVAL_REQUIRED"
    assert result.approval_id == 7
    assert target.requests == []


def test_blocked_does_not_send():
    target = FakeTarget()

    result = tool(FakeAgentGate("BLOCKED", "BLOCKED"), target).run()

    assert result.status == "BLOCKED"
    assert target.requests == []


def test_gate_failure_is_fail_closed():
    target = FakeTarget()

    result = tool(FakeAgentGate(http_status=500), target).run()

    assert result.status == "FAILED"
    assert "500" in result.error
    assert target.requests == []


def test_target_error_is_failed():
    result = tool(FakeAgentGate(), FakeTarget(status_code=502)).run()

    assert result.status == "FAILED"
    assert result.response_status == 502
