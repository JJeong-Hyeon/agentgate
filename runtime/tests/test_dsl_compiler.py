import json
import uuid
from pathlib import Path

import httpx
import pytest
from langchain_core.language_models import FakeListChatModel
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.types import Command

from app.dsl import Workflow
from app.dsl.compiler import CompileError, WorkflowCompiler, pick_route, render
from app.governance.agentgate_client import AgentGateClient
from tests.fakes import FakeAgentGate, FakeTarget

EXAMPLE = json.loads((Path(__file__).parent.parent / "examples" / "research.json").read_text())


class Llm:
    """LLM factory returning scripted replies in call order and recording each call."""

    def __init__(self, *replies: str):
        self.model = FakeListChatModel(responses=list(replies))
        self.calls: list[tuple[str | None, float | None]] = []

    def __call__(self, model, temperature):
        self.calls.append((model, temperature))
        return self.model


def compile_and_run(dsl: dict, llm: Llm, gate=None, target=None, task="Compare runtimes"):
    gate_client = (
        AgentGateClient("http://agentgate", "runtime-agent", "k", httpx.MockTransport(gate))
        if gate
        else None
    )
    compiler = WorkflowCompiler(llm, gate_client, httpx.MockTransport(target or FakeTarget()))
    graph = compiler.compile(Workflow.model_validate(dsl), InMemorySaver())
    config = {"configurable": {"thread_id": str(uuid.uuid4())}}
    state = graph.invoke({"task": task, "workflow": dsl, "revisions": {}}, config)
    return graph, config, state


def linear(*middle: dict, extra_edges=()) -> dict:
    nodes = [{"id": "start", "type": "START"}, *middle, {"id": "end", "type": "END"}]
    ids = [n["id"] for n in nodes]
    edges = [{"source": a, "target": b} for a, b in zip(ids, ids[1:], strict=False)]
    return {"workflowId": "wf", "nodes": nodes, "edges": [*edges, *extra_edges]}


def test_render_fills_known_values_and_blanks_missing():
    assert render("{task} / {plan} / {{literal}}", {"task": "t"}) == "t /  / {literal}"


@pytest.mark.parametrize(
    ("reply", "expected"),
    [("web", "web"), ("I would use the WEB route.", "web"), ("no idea", "db"), ("web-db", "web")],
)
def test_pick_route(reply, expected):
    assert pick_route(reply, ["db", "web"]) == expected


def test_research_example_reproduces_research_graph():
    target = FakeTarget()

    _, _, state = compile_and_run(
        EXAMPLE, Llm("1. step", "findings v1", "VERDICT: APPROVE ok"), FakeAgentGate(), target
    )

    assert state["plan"] == "1. step"
    assert state["findings"] == "findings v1"
    assert state["review"] == "VERDICT: APPROVE ok"
    assert [r["status"] for r in state["tool_results"]] == ["EXECUTED"]
    assert json.loads(target.requests[0].content) == {
        "task": "Compare runtimes",
        "findings": "findings v1",
        "review": "VERDICT: APPROVE ok",
    }


def test_reviewer_revise_loops_back():
    llm = Llm("1. step", "v1", "VERDICT: REVISE more", "v2", "VERDICT: APPROVE")

    _, _, state = compile_and_run(EXAMPLE, llm, FakeAgentGate())

    assert state["findings"] == "v2"
    assert state["revisions"] == {"review": 2}


def test_reviewer_stops_after_max_revisions_without_report():
    gate = FakeAgentGate()
    llm = Llm("1. step", "v1", "VERDICT: REVISE", "v2", "VERDICT: REVISE", "v3")

    _, _, state = compile_and_run(EXAMPLE, llm, gate)

    assert state["findings"] == "v2"
    assert gate.requests == []


def test_http_tool_waits_for_approval_and_resumes():
    gate, target = FakeAgentGate("APPROVAL_REQUIRED", "HIGH", 5), FakeTarget()
    graph, config, _ = compile_and_run(
        EXAMPLE, Llm("1. step", "f", "VERDICT: APPROVE"), gate, target
    )
    assert graph.get_state(config).interrupts[0].value == {"tool": "report", "approval_id": 5}

    state = graph.invoke(Command(resume={"decision": "APPROVED"}), config)

    assert state["tool_results"][0]["status"] == "EXECUTED"
    assert len(gate.requests) == 1
    assert len(target.requests) == 1


def test_http_tool_output_is_available_to_later_nodes():
    dsl = linear(
        {
            "id": "lookup",
            "type": "HTTP_TOOL",
            "config": {"action": "VIEW_DATA", "url": "http://api/x", "payloadKeys": ["task"]},
        },
        {"id": "answer", "type": "LLM", "config": {"prompt": "Data: {lookup}"}},
    )

    _, _, state = compile_and_run(dsl, Llm("done"), FakeAgentGate())

    assert state["lookup"] == '{"ok":true}'
    assert state["answer"] == "done"


def test_blocked_http_tool_output_is_its_status():
    dsl = linear(
        {"id": "wipe", "type": "HTTP_TOOL", "config": {"action": "DELETE_DATA", "url": "http://x"}}
    )

    _, _, state = compile_and_run(dsl, Llm(), FakeAgentGate("BLOCKED", "BLOCKED"))

    assert state["wipe"] == "BLOCKED"


def test_llm_node_passes_model_and_temperature():
    llm = Llm("hi")
    dsl = linear(
        {"id": "a", "type": "LLM", "config": {"prompt": "{task}", "model": "m", "temperature": 0.3}}
    )

    compile_and_run(dsl, llm)

    assert llm.calls == [("m", 0.3)]


def router_workflow(cond_cases: dict) -> dict:
    return {
        "workflowId": "router",
        "nodes": [
            {"id": "start", "type": "START"},
            {
                "id": "route",
                "type": "ROUTER",
                "config": {"prompt": "{task}", "routes": ["db", "web"]},
            },
            {"id": "db_agent", "type": "LLM", "config": {"prompt": "db"}},
            {"id": "web_agent", "type": "LLM", "config": {"prompt": "web"}},
            {
                "id": "check",
                "type": "CONDITION",
                "config": {"key": "route", "cases": cond_cases, "default": "other"},
            },
            {"id": "end", "type": "END"},
        ],
        "edges": [
            {"source": "start", "target": "route"},
            {"source": "route", "target": "db_agent", "label": "db"},
            {"source": "route", "target": "web_agent", "label": "web"},
            {"source": "db_agent", "target": "check"},
            {"source": "web_agent", "target": "check"},
            *[{"source": "check", "target": "end", "label": label} for label in cond_cases],
            {"source": "check", "target": "end", "label": "other"},
        ],
    }


def test_router_follows_chosen_route():
    _, _, state = compile_and_run(router_workflow({"was_web": "web"}), Llm("web", "searched"))

    assert state["route"] == "web"
    assert state["web_agent"] == "searched"
    assert "db_agent" not in state


def test_parallel_fan_out_runs_both_branches():
    dsl = {
        "workflowId": "fan",
        "nodes": [
            {"id": "start", "type": "START"},
            {"id": "a", "type": "LLM", "config": {"prompt": "a"}},
            {"id": "b", "type": "LLM", "config": {"prompt": "b"}},
            {"id": "c", "type": "LLM", "config": {"prompt": "c"}},
            {"id": "end", "type": "END"},
        ],
        "edges": [
            {"source": "start", "target": "a"},
            {"source": "a", "target": "b"},
            {"source": "a", "target": "c"},
            {"source": "b", "target": "end"},
            {"source": "c", "target": "end"},
        ],
    }

    _, _, state = compile_and_run(dsl, Llm("x", "y", "z"))

    assert {state["b"], state["c"]} == {"y", "z"}


def approval_workflow() -> dict:
    return linear(
        {
            "id": "confirm",
            "type": "APPROVAL",
            "config": {"message": "Send a report about {task}?", "labels": ["PII"]},
        },
        {"id": "answer", "type": "LLM", "config": {"prompt": "{task}"}},
    )


def test_approval_waits_then_continues_when_approved():
    gate = FakeAgentGate("APPROVAL_REQUIRED", "LOW", 4)
    graph, config, _ = compile_and_run(approval_workflow(), Llm("answered"), gate)

    assert graph.get_state(config).interrupts[0].value == {"tool": "confirm", "approval_id": 4}
    assert gate.bodies[0] == {
        "agentId": "runtime-agent",
        "action": "HUMAN_APPROVAL",
        "target": None,
        "labels": ["PII"],
        "executionId": config["configurable"]["thread_id"],
        "requireApproval": True,
        "reason": "Send a report about Compare runtimes?",
    }

    state = graph.invoke(Command(resume={"decision": "APPROVED"}), config)

    assert state["confirm"] == "APPROVED"
    assert state["answer"] == "answered"
    assert len(gate.requests) == 1


def test_rejected_approval_ends_execution():
    graph, config, _ = compile_and_run(
        approval_workflow(), Llm("answered"), FakeAgentGate("APPROVAL_REQUIRED", "LOW", 4)
    )

    state = graph.invoke(Command(resume={"decision": "REJECTED"}), config)

    assert state["confirm"] == "REJECTED"
    assert "answer" not in state
    assert graph.get_state(config).next == ()


@pytest.mark.parametrize(
    ("gate", "expected"),
    [(FakeAgentGate("BLOCKED", "BLOCKED"), "BLOCKED"), (FakeAgentGate(http_status=500), "FAILED")],
)
def test_blocked_or_failed_approval_ends_without_waiting(gate, expected):
    graph, config, state = compile_and_run(approval_workflow(), Llm("answered"), gate)

    assert state["confirm"] == expected
    assert state["tool_results"][0]["status"] == expected
    assert "answer" not in state
    assert not graph.get_state(config).interrupts


def test_approval_requires_agentgate():
    with pytest.raises(CompileError, match="AgentGate"):
        WorkflowCompiler(Llm()).compile(Workflow.model_validate(approval_workflow()))


def test_http_tool_requires_agentgate():
    dsl = linear({"id": "t", "type": "HTTP_TOOL", "config": {"action": "A", "url": "http://x"}})

    with pytest.raises(CompileError, match="AgentGate"):
        WorkflowCompiler(Llm()).compile(Workflow.model_validate(dsl))
