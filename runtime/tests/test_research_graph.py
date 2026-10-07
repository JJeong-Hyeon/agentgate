import os
import uuid

import httpx
import pytest
from langchain_core.language_models import FakeListChatModel
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.types import Command

from app.governance.agentgate_client import AgentGateClient
from app.graph.research import build_research_graph
from app.nodes.agent import parse_verdict
from app.tools.http import HttpTool, HttpToolSpec
from tests.fakes import FakeAgentGate, FakeTarget


def run(responses: list[str], max_revisions: int = 1, checkpointer=None, report_tool=None):
    graph = build_research_graph(
        FakeListChatModel(responses=responses),
        checkpointer or InMemorySaver(),
        max_revisions,
        report_tool,
    )
    config = {"configurable": {"thread_id": str(uuid.uuid4())}}
    return graph, config, graph.invoke({"task": "Compare Ollama and vLLM", "revisions": 0}, config)


@pytest.mark.parametrize(
    ("review", "expected"),
    [
        ("VERDICT: APPROVE\nLooks good", "APPROVE"),
        ("verdict: revise - missing detail", "REVISE"),
        ("I think it is fine", "REVISE"),
    ],
)
def test_parse_verdict(review, expected):
    assert parse_verdict(review) == expected


def test_runs_planner_researcher_reviewer_in_order():
    _, _, state = run(["1. step", "findings v1", "VERDICT: APPROVE ok"])

    assert state["plan"] == "1. step"
    assert state["findings"] == "findings v1"
    assert state["verdict"] == "APPROVE"
    assert state["revisions"] == 1


def test_revise_loops_back_to_researcher():
    _, _, state = run(
        ["1. step", "findings v1", "VERDICT: REVISE add numbers", "findings v2", "VERDICT: APPROVE"]
    )

    assert state["findings"] == "findings v2"
    assert state["verdict"] == "APPROVE"
    assert state["revisions"] == 2


def test_revision_limit_stops_loop():
    _, _, state = run(
        ["1. step", "v1", "VERDICT: REVISE", "v2", "VERDICT: REVISE", "v3", "VERDICT: APPROVE"],
        max_revisions=1,
    )

    assert state["findings"] == "v2"
    assert state["verdict"] == "REVISE"
    assert state["revisions"] == 2


def test_state_is_checkpointed():
    graph, config, _ = run(["1. step", "findings", "VERDICT: APPROVE"])

    snapshot = graph.get_state(config)

    assert snapshot.values["findings"] == "findings"
    assert snapshot.next == ()


@pytest.mark.integration
@pytest.mark.skipif(not os.getenv("RUNTIME_DATABASE_URL"), reason="RUNTIME_DATABASE_URL not set")
def test_state_is_checkpointed_in_postgres():
    from app.graph.checkpointer import open_checkpointer

    with open_checkpointer(os.environ["RUNTIME_DATABASE_URL"]) as saver:
        _, config, _ = run(["1. step", "findings", "VERDICT: APPROVE"], checkpointer=saver)

    with open_checkpointer(os.environ["RUNTIME_DATABASE_URL"]) as saver:
        restored = build_research_graph(FakeListChatModel(responses=["x"]), saver)
        assert restored.get_state(config).values["findings"] == "findings"


def report_tool(gate: FakeAgentGate, target: FakeTarget) -> HttpTool:
    client = AgentGateClient("http://agentgate", "runtime-agent", "k", httpx.MockTransport(gate))
    spec = HttpToolSpec(name="report", action="SEND_REPORT", url="http://hook/report")
    return HttpTool(spec, client, httpx.MockTransport(target))


def test_approved_findings_are_reported():
    target = FakeTarget()

    _, _, state = run(
        ["1. step", "findings", "VERDICT: APPROVE"],
        report_tool=report_tool(FakeAgentGate(), target),
    )

    assert [r["status"] for r in state["tool_results"]] == ["EXECUTED"]
    assert b'"findings":"findings"' in target.requests[0].content


def run_until_approval(gate: FakeAgentGate, target: FakeTarget):
    return run(["1. step", "findings", "VERDICT: APPROVE"], report_tool=report_tool(gate, target))


def test_report_pauses_when_agentgate_requires_approval():
    target = FakeTarget()

    graph, config, _ = run_until_approval(FakeAgentGate("APPROVAL_REQUIRED", "HIGH", 3), target)

    snapshot = graph.get_state(config)
    assert snapshot.interrupts[0].value == {"tool": "report", "approval_id": 3}
    assert snapshot.values["pending_tool"]["approval_id"] == 3
    assert snapshot.values.get("tool_results", []) == []
    assert target.requests == []


def test_approved_resume_executes_tool_without_asking_agentgate_again():
    gate, target = FakeAgentGate("APPROVAL_REQUIRED", "HIGH", 3), FakeTarget()
    graph, config, _ = run_until_approval(gate, target)

    state = graph.invoke(Command(resume={"decision": "APPROVED"}), config)

    assert [r["status"] for r in state["tool_results"]] == ["EXECUTED"]
    assert state["tool_results"][0]["approval_id"] == 3
    assert len(target.requests) == 1
    assert len(gate.requests) == 1
    assert graph.get_state(config).next == ()


def test_rejected_resume_skips_tool():
    gate, target = FakeAgentGate("APPROVAL_REQUIRED", "HIGH", 3), FakeTarget()
    graph, config, _ = run_until_approval(gate, target)

    state = graph.invoke(Command(resume={"decision": "REJECTED"}), config)

    assert [r["status"] for r in state["tool_results"]] == ["REJECTED"]
    assert target.requests == []
    assert graph.get_state(config).next == ()


def test_blocked_report_is_recorded_without_pausing():
    target = FakeTarget()

    graph, config, state = run_until_approval(FakeAgentGate("BLOCKED", "BLOCKED"), target)

    assert [r["status"] for r in state["tool_results"]] == ["BLOCKED"]
    assert not graph.get_state(config).interrupts
    assert target.requests == []


def test_report_sends_execution_id_to_agentgate():
    gate = FakeAgentGate()

    _, config, _ = run(
        ["1. step", "findings", "VERDICT: APPROVE"], report_tool=report_tool(gate, FakeTarget())
    )

    assert gate.bodies[0]["executionId"] == config["configurable"]["thread_id"]


def test_unapproved_findings_are_not_reported():
    gate = FakeAgentGate()

    _, _, state = run(
        ["1. step", "v1", "VERDICT: REVISE", "v2", "VERDICT: REVISE"],
        report_tool=report_tool(gate, FakeTarget()),
    )

    assert state.get("tool_results", []) == []
    assert gate.requests == []
