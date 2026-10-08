"""A supervisor agent delegating to worker agents, each governed under its own name."""

import json

import httpx
import pytest
from langchain_core.messages import ToolMessage
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.types import Command

from app.agents.resolve import attach_tool_schemas
from app.dsl import Workflow
from app.dsl.compiler import CompileError, WorkflowCompiler
from app.events import EventReporter, run_graph
from app.governance.agentgate_client import AgentGateClient
from app.runner import WorkflowRunner
from tests.fake_llm import ScriptedChatModel, calls
from tests.fakes import FakeAgentGateEvents
from tests.test_agent_node import CATALOG, CONFIG, ECHO_SERVER, PolicyGate


def agent(agent_id: str, tools=(), delegates=(), **extra) -> dict:
    return {
        "agentId": agent_id,
        "version": 1,
        "systemPrompt": f"You are {agent_id}.",
        "tools": list(tools),
        "delegates": list(delegates),
        "maxSteps": 4,
        **extra,
    }


ECHO = {"server": "local", "tool": "echo", "permission": "AUTO"}
ADD = {"server": "local", "tool": "add", "permission": "AUTO"}


def team(**agents) -> dict:
    defaults = {
        "lead": agent(
            "lead",
            delegates=[
                {"agentId": "research", "permission": "AUTO"},
                {"agentId": "data", "permission": "BLOCKED"},
            ],
            description="Leads the team",
        ),
        "research": agent("research", tools=[ECHO, ADD], description="Finds things out"),
        "data": agent("data"),
    }
    return {
        "workflowId": "team",
        "nodes": [
            {"id": "start", "type": "START"},
            {"id": "helper", "type": "AGENT", "config": {"agentId": "lead", "prompt": "{task}"}},
            {"id": "end", "type": "END"},
        ],
        "edges": [
            {"source": "start", "target": "helper"},
            {"source": "helper", "target": "end"},
        ],
        "agents": {**defaults, **agents},
    }


def build(dsl: dict, llm: ScriptedChatModel, gate: PolicyGate, checkpointer=None):
    workflow = attach_tool_schemas(Workflow.model_validate(dsl), CATALOG)
    client = AgentGateClient(
        "http://agentgate", "runtime-agent", transport=httpx.MockTransport(gate), runtime_token="t"
    )
    compiler = WorkflowCompiler(lambda m, t: llm, client, mcp_servers={"local": ECHO_SERVER})
    graph = compiler.compile(workflow, checkpointer or InMemorySaver())
    state = {"task": "team task", "workflow": workflow.model_dump(mode="json", by_alias=True)}
    return graph, state, compiler


def delegate(task: str):
    return calls(("delegate__research", {"task": task}))


def test_supervisor_delegates_and_the_worker_acts_under_its_own_name():
    llm = ScriptedChatModel.of(
        delegate("say hi"),
        calls(("local__echo", {"text": "hi"})),
        "research says: echo: hi",
        "Team result: research says: echo: hi",
    )
    gate = PolicyGate()
    graph, state, _ = build(team(), llm, gate)

    result = graph.invoke(state, CONFIG)

    assert result["helper"] == "Team result: research says: echo: hi"
    lead_first, research_first = llm.log[0], llm.log[1]
    # Blocked delegates are not offered; allowed ones are, with the agent's description.
    [offered] = lead_first["tools"]
    assert offered["function"]["name"] == "delegate__research"
    assert offered["function"]["description"] == "Hand a task to agent 'research': Finds things out"
    assert offered["function"]["parameters"]["required"] == ["task"]
    # The worker gets the task as its prompt and its own tools.
    assert research_first["messages"][0].content == "You are research."
    assert research_first["messages"][1].content == "say hi"
    assert [t["function"]["name"] for t in research_first["tools"]] == ["local__echo", "local__add"]
    # The supervisor sees the worker's answer as the tool result.
    assert llm.log[3]["messages"][-1] == ToolMessage(
        content="research says: echo: hi",
        tool_call_id="call-0-delegate__research",
        name="delegate__research",
    )
    delegation, worker_call = gate.bodies
    assert delegation["agentId"] == "lead"
    assert delegation["action"] == "AGENT:research"
    assert "delegatedBy" not in delegation
    assert worker_call["agentId"] == "research"
    assert worker_call["action"] == "MCP:local:echo"
    assert worker_call["delegatedBy"] == "lead"
    assert worker_call["reason"].startswith(
        "Agent 'research' (v1, delegated by lead) wants to call"
    )
    assert [r["tool"] for r in result["tool_results"]] == ["helper:agent/research"]


def test_an_approval_inside_the_worker_pauses_and_resumes_the_execution():
    llm = ScriptedChatModel.of(
        delegate("add 2 and 3"),
        calls(("local__add", {"a": 2, "b": 3})),
        "It is 5.",
        "Team result: 5",
    )
    gate = PolicyGate(add="APPROVAL_REQUIRED")
    graph, state, _ = build(team(), llm, gate)

    graph.invoke(state, CONFIG)
    snapshot = graph.get_state(CONFIG)
    assert [i.value for i in snapshot.interrupts] == [
        {"tool": "research:local/add", "approval_id": 101}
    ]

    result = graph.invoke(Command(resume={"decision": "APPROVED"}), CONFIG)

    assert result["helper"] == "Team result: 5"
    # Nothing ran twice: no extra model turns, AgentGate asked once per call.
    assert len(llm.log) == 4
    assert [b["action"] for b in gate.bodies] == ["AGENT:research", "MCP:local:add"]
    assert llm.log[2]["messages"][-1].content == "5"


def test_a_rejection_inside_the_worker_is_handled_by_the_worker():
    llm = ScriptedChatModel.of(
        delegate("add"),
        calls(("local__add", {"a": 1, "b": 1})),
        "I could not add.",
        "Team result: nothing",
    )
    graph, state, _ = build(team(), llm, PolicyGate(add="APPROVAL_REQUIRED"))

    graph.invoke(state, CONFIG)
    result = graph.invoke(Command(resume={"decision": "REJECTED"}), CONFIG)

    assert "human approver rejected" in llm.log[2]["messages"][-1].content
    assert llm.log[3]["messages"][-1].content == "I could not add."
    assert result["helper"] == "Team result: nothing"


def test_a_denied_delegation_is_reported_to_the_supervisor():
    llm = ScriptedChatModel.of(delegate("x"), "Did it myself.")
    graph, state, _ = build(team(), llm, PolicyGate(research="BLOCKED"))

    result = graph.invoke(state, CONFIG)

    assert llm.log[1]["messages"][-1].content.startswith("Blocked by governance policy")
    assert result["helper"] == "Did it myself."
    assert result["tool_results"][0]["status"] == "BLOCKED"


def test_a_resumed_execution_rebuilds_the_team_from_saved_state():
    llm = ScriptedChatModel.of(
        delegate("add"), calls(("local__add", {"a": 1, "b": 1})), "2", "Team: 2"
    )
    gate = PolicyGate(add="APPROVAL_REQUIRED")
    checkpointer = InMemorySaver()
    graph, state, compiler = build(team(), llm, gate, checkpointer)
    graph.invoke(state, CONFIG)

    restored = WorkflowRunner(compiler, checkpointer).graph_for_execution("exec-1")
    result = restored.invoke(Command(resume={"decision": "APPROVED"}), CONFIG)

    assert result["helper"] == "Team: 2"


def test_worker_steps_are_reported_under_the_workflow_node():
    llm = ScriptedChatModel.of(
        delegate("say hi"), calls(("local__echo", {"text": "hi"})), "hi back", "done"
    )
    graph, state, _ = build(team(), llm, PolicyGate())
    sink = FakeAgentGateEvents()

    run_graph(graph, state, CONFIG, EventReporter("http://ag", "t", httpx.MockTransport(sink)))

    completed = [e for e in sink.events if e["type"] == "NODE_COMPLETED"]
    assert {e["nodeId"] for e in completed} == {"helper"}
    worker = [
        json.loads(e["output"]).get("agent") for e in completed if e["step"].startswith("research")
    ]
    assert worker and all(t["agent"] == "research" for t in worker if t)
    assert all(t["delegated_by"] == "lead" for t in worker if t)
    echo = next(t for t in worker if t and t.get("kind") == "result")
    assert echo["content"] == "echo: hi"
    assert sink.types[-1] == "EXECUTION_COMPLETED"


def test_delegation_graphs_must_be_resolved_acyclic_and_shallow():
    def compile_only(dsl):
        client = AgentGateClient("http://agentgate", "runtime-agent", "k")
        WorkflowCompiler(lambda m, t: None, client, mcp_servers={"local": ECHO_SERVER}).compile(
            attach_tool_schemas(Workflow.model_validate(dsl), CATALOG)
        )

    dsl = team()
    del dsl["agents"]["research"]
    with pytest.raises(CompileError, match="delegate 'research' was not resolved"):
        compile_only(dsl)

    looped = agent("research", delegates=[{"agentId": "lead", "permission": "AUTO"}])
    with pytest.raises(CompileError, match="delegation cycle lead > research > lead"):
        compile_only(team(research=looped))

    chain = {
        "research": agent("research", delegates=[{"agentId": "a", "permission": "AUTO"}]),
        "a": agent("a", delegates=[{"agentId": "b", "permission": "AUTO"}]),
        "b": agent("b", delegates=[{"agentId": "c", "permission": "AUTO"}]),
        "c": agent("c"),
    }
    with pytest.raises(CompileError, match="delegation deeper than 3"):
        compile_only(team(**chain))
