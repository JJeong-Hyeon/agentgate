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


def test_idempotency_key_is_sent_when_given_and_not_saved_with_the_result():
    from app.tools.base import ToolResult

    target = FakeTarget()
    http = tool(FakeAgentGate("ALLOWED", "LOW"), target)
    authorized = ToolResult(tool="report", status="ALLOWED", idempotency_key="exec-1:report")

    result = http.execute_timed({"msg": "hi"}, authorized)

    assert target.requests[0].headers["Idempotency-Key"] == "exec-1:report"
    assert "idempotency_key" not in result.model_dump()
    tool(FakeAgentGate("ALLOWED", "LOW"), target).run({"msg": "hi"})
    assert "Idempotency-Key" not in target.requests[1].headers


def test_the_approver_gets_the_arguments_with_secrets_masked_and_the_hash_of_the_real_ones():
    from app.governance.payload import payload_hash

    gate = FakeAgentGate("APPROVAL_REQUIRED", "HIGH", 7)
    payload = {
        "to": "kim",
        "auth": {"api_key": "sk-123", "note": "x"},
        "items": [{"password": "p"}],
    }

    tool(gate).authorize("exec-1", payload=payload)

    preview = gate.bodies[0]["payloadPreview"]
    assert '"to": "kim"' in preview and "sk-123" not in preview and '"p"' not in preview
    assert preview.count("***") == 2
    assert gate.bodies[0]["payloadHash"] == payload_hash(
        "SEND_REPORT", "http://hook/report", payload
    )
