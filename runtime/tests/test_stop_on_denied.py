"""A denied tool (blocked by AgentGate or rejected by an approver) stops the execution,
unless the node is configured to continue."""

import copy

import pytest

from tests.fakes import FakeAgentGate, FakeTarget
from tests.test_executions_endpoint import REPLIES, events_sinks, make_client, resume, start
from tests.workflows import research


def report_then_summary(on_denied: str | None = None) -> dict:
    """The research workflow with one more LLM step after the report tool."""
    workflow = copy.deepcopy(research())
    report = next(n for n in workflow["nodes"] if n["id"] == "report")
    if on_denied:
        report["config"]["onDenied"] = on_denied
    workflow["nodes"].append({"id": "summary", "type": "LLM", "config": {"prompt": "{report}"}})
    workflow["edges"] = [e for e in workflow["edges"] if e["source"] != "report"]
    workflow["edges"] += [
        {"source": "report", "target": "summary"},
        {"source": "summary", "target": "end"},
    ]
    return workflow


def test_blocked_tool_stops_the_execution():
    target = FakeTarget()
    client = make_client(REPLIES + ["summary"], FakeAgentGate("BLOCKED", "BLOCKED"), target)

    body = start(client, report_then_summary())

    assert body["status"] == "STOPPED"
    assert body["state"]["stopped"] == {
        "node": "report",
        "status": "BLOCKED",
        "reason": "report: blocked by AgentGate",
    }
    assert "summary" not in body["state"]
    assert target.requests == []
    assert events_sinks[-1].events[-1] == {
        **events_sinks[-1].events[-1],
        "type": "EXECUTION_STOPPED",
        "error": "report: blocked by AgentGate",
    }


def test_rejected_tool_stops_the_execution_after_resume():
    target = FakeTarget()
    client = make_client(
        REPLIES + ["summary"], FakeAgentGate("APPROVAL_REQUIRED", "HIGH", 7), target
    )
    execution_id = start(client, report_then_summary())["execution_id"]

    resume(client, execution_id, decision="REJECTED")

    after = client.get(f"/runtime/executions/{execution_id}").json()
    assert after["status"] == "STOPPED"
    assert after["state"]["stopped"]["reason"] == "report: rejected by an approver"
    assert "summary" not in after["state"]
    assert events_sinks[-1].types[-1] == "EXECUTION_STOPPED"


@pytest.mark.parametrize(
    ("gate", "decision", "status"),
    [
        (FakeAgentGate("BLOCKED", "BLOCKED"), None, "BLOCKED"),
        (FakeAgentGate("APPROVAL_REQUIRED", "HIGH", 7), "REJECTED", "REJECTED"),
    ],
)
def test_continue_records_the_denial_and_goes_on(gate, decision, status):
    target = FakeTarget()
    client = make_client(REPLIES + ["summary"], gate, target)

    body = start(client, report_then_summary("CONTINUE"))
    if decision:
        resume(client, body["execution_id"], decision=decision)
        body = client.get(f"/runtime/executions/{body['execution_id']}").json()

    assert body["status"] == "COMPLETED"
    assert body["state"]["report"] == status
    assert body["state"]["summary"] == "summary"
    assert body["state"].get("stopped") is None
    assert events_sinks[-1].types[-1] == "EXECUTION_COMPLETED"


def test_rejected_approval_node_stops_the_execution():
    workflow = {
        "workflowId": "confirm",
        "nodes": [
            {"id": "start", "type": "START"},
            {"id": "confirm", "type": "APPROVAL", "config": {"message": "Go?"}},
            {"id": "answer", "type": "LLM", "config": {"prompt": "{task}"}},
            {"id": "end", "type": "END"},
        ],
        "edges": [
            {"source": "start", "target": "confirm"},
            {"source": "confirm", "target": "answer"},
            {"source": "answer", "target": "end"},
        ],
    }
    client = make_client(["answer"], FakeAgentGate("APPROVAL_REQUIRED", "HIGH", 7), FakeTarget())
    execution_id = start(client, workflow)["execution_id"]

    resume(client, execution_id, decision="REJECTED")

    after = client.get(f"/runtime/executions/{execution_id}").json()
    assert after["status"] == "STOPPED"
    assert after["state"]["stopped"]["reason"] == "confirm: rejected by an approver"
    assert "answer" not in after["state"]


def test_unknown_on_denied_value_is_rejected():
    workflow = report_then_summary("IGNORE")
    client = make_client(REPLIES, FakeAgentGate(), FakeTarget())

    response = client.post("/runtime/executions", json={"task": "t", "workflow": workflow})

    assert response.status_code == 422
