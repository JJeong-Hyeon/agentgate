import json
import sys
from pathlib import Path

import httpx
import pytest
from langchain_core.messages import HumanMessage, SystemMessage, ToolMessage
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.types import Command
from pydantic import ValidationError

from app.agents.resolve import attach_tool_schemas
from app.dsl import Workflow, validate_workflow
from app.dsl.compiler import CompileError, WorkflowCompiler
from app.governance.agentgate_client import AgentGateClient
from app.nodes.agent import FINAL_TURN_NOTE, function_names
from app.runner import WorkflowRunner
from app.tools.catalog import ToolCatalog
from app.tools.mcp import McpServerConfig
from tests.fake_llm import ScriptedChatModel, calls

ECHO_SERVER = McpServerConfig(
    command=sys.executable, args=[str(Path(__file__).parent / "mcp_echo_server.py")]
)
CATALOG = ToolCatalog({"local": ECHO_SERVER})


class PolicyGate:
    """AgentGate stand-in deciding per action; records requests."""

    def __init__(self, **decisions: str):
        self.decisions = decisions
        self.bodies: list[dict] = []
        self.next_approval = 100

    def __call__(self, request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        self.bodies.append(body)
        status = self.decisions.get(body["action"].split(":")[-1], "ALLOWED")
        decision = {"status": status, "riskLevel": "LOW", "approvalId": None, "basis": "POLICY"}
        if status == "APPROVAL_REQUIRED":
            self.next_approval += 1
            decision.update(riskLevel="HIGH", approvalId=self.next_approval)
        if status == "BLOCKED":
            decision.update(riskLevel="BLOCKED", basis="TOOL_NOT_GRANTED")
        return httpx.Response(200, json=decision)


def agent_spec(**overrides) -> dict:
    spec = {
        "agentId": "note-agent",
        "version": 3,
        "systemPrompt": "You are careful.",
        "tools": [
            {"server": "local", "tool": "echo", "permission": "AUTO"},
            {"server": "local", "tool": "add", "permission": "APPROVAL"},
            {"server": "local", "tool": "fail", "permission": "BLOCKED"},
        ],
        "maxSteps": 4,
    }
    return {**spec, **overrides}


def agent_workflow(**spec_overrides) -> dict:
    return {
        "workflowId": "agentic",
        "nodes": [
            {"id": "start", "type": "START"},
            {
                "id": "helper",
                "type": "AGENT",
                "config": {"agentId": "note-agent", "prompt": "Task: {task}"},
            },
            {"id": "after", "type": "LLM", "config": {"prompt": "Summarize: {helper}"}},
            {"id": "end", "type": "END"},
        ],
        "edges": [
            {"source": "start", "target": "helper"},
            {"source": "helper", "target": "after"},
            {"source": "after", "target": "end"},
        ],
        "agents": {"note-agent": agent_spec(**spec_overrides)},
    }


def build(dsl: dict, llm: ScriptedChatModel, gate: PolicyGate, checkpointer=None):
    workflow = attach_tool_schemas(Workflow.model_validate(dsl), CATALOG)
    client = AgentGateClient(
        "http://agentgate", "runtime-agent", transport=httpx.MockTransport(gate), runtime_token="t"
    )
    compiler = WorkflowCompiler(lambda m, t: llm, client, mcp_servers={"local": ECHO_SERVER})
    graph = compiler.compile(workflow, checkpointer or InMemorySaver())
    state = {
        "task": "add things",
        "workflow": workflow.model_dump(mode="json", by_alias=True),
        "revisions": {},
    }
    return graph, state


CONFIG = {"configurable": {"thread_id": "exec-1"}}


def test_allowed_tool_call_runs_and_its_result_goes_back_to_the_model():
    llm = ScriptedChatModel.of(calls(("local__echo", {"text": "hi"})), "It said hi.", "summary")
    gate = PolicyGate()
    graph, state = build(agent_workflow(), llm, gate)

    result = graph.invoke(state, CONFIG)

    assert result["helper"] == "It said hi."
    assert result["after"] == "summary"
    first, second = llm.log[0], llm.log[1]
    assert isinstance(first["messages"][0], SystemMessage)
    assert first["messages"][1] == HumanMessage("Task: add things")
    # BLOCKED tools are never offered to the model.
    assert [t["function"]["name"] for t in first["tools"]] == ["local__echo", "local__add"]
    assert first["tools"][1]["function"]["parameters"]["required"] == ["a", "b"]
    tool_message = second["messages"][-1]
    assert isinstance(tool_message, ToolMessage)
    assert tool_message.content == "echo: hi"
    # Evaluated on behalf of the agent version, with the call shown to approvers.
    [body] = gate.bodies
    assert body["agentId"] == "note-agent"
    assert body["agentVersion"] == 3
    assert body["action"] == "MCP:local:echo"
    assert body["executionId"] == "exec-1"
    assert body["reason"] == (
        'Agent \'note-agent\' (v3) wants to call local/echo with {"text": "hi"}'
    )
    [record] = result["tool_results"]
    assert record["tool"] == "helper:local/echo"
    assert record["status"] == "EXECUTED"


def test_approval_required_waits_then_runs_when_approved():
    llm = ScriptedChatModel.of(calls(("local__add", {"a": 2, "b": 3})), "2+3=5", "done")
    gate = PolicyGate(add="APPROVAL_REQUIRED")
    graph, state = build(agent_workflow(), llm, gate)

    graph.invoke(state, CONFIG)
    snapshot = graph.get_state(CONFIG)
    assert snapshot.interrupts[0].value == {"tool": "helper:local/add", "approval_id": 101}
    assert snapshot.next == ("helper.approval",)

    result = graph.invoke(Command(resume={"decision": "APPROVED"}), CONFIG)

    assert result["helper"] == "2+3=5"
    assert llm.log[1]["messages"][-1].content == "5"
    assert len(gate.bodies) == 1  # not asked again on resume
    assert result["tool_results"][0]["status"] == "EXECUTED"
    assert result["tool_results"][0]["approval_id"] == 101


def test_rejected_call_is_reported_to_the_model_which_continues():
    llm = ScriptedChatModel.of(calls(("local__add", {"a": 2, "b": 3})), "Could not add.", "done")
    graph, state = build(agent_workflow(), llm, PolicyGate(add="APPROVAL_REQUIRED"))

    graph.invoke(state, CONFIG)
    result = graph.invoke(Command(resume={"decision": "REJECTED"}), CONFIG)

    assert result["helper"] == "Could not add."
    assert "human approver rejected" in llm.log[1]["messages"][-1].content
    assert result["tool_results"][0]["status"] == "REJECTED"


def test_blocked_call_never_runs_and_the_model_is_told_why():
    llm = ScriptedChatModel.of(calls(("local__echo", {"text": "x"})), "Blocked.", "done")
    graph, state = build(agent_workflow(), llm, PolicyGate(echo="BLOCKED"))

    result = graph.invoke(state, CONFIG)

    told = llm.log[1]["messages"][-1].content
    assert told.startswith("Blocked by governance policy (TOOL_NOT_GRANTED)")
    assert result["tool_results"][0]["status"] == "BLOCKED"
    assert result["helper"] == "Blocked."


def test_several_calls_in_one_turn_are_handled_in_order():
    llm = ScriptedChatModel.of(
        calls(("local__echo", {"text": "a"}), ("local__echo", {"text": "b"})), "ab", "done"
    )
    graph, state = build(agent_workflow(), llm, PolicyGate())

    result = graph.invoke(state, CONFIG)

    answers = [m.content for m in llm.log[1]["messages"] if isinstance(m, ToolMessage)]
    assert answers == ["echo: a", "echo: b"]
    assert [r["status"] for r in result["tool_results"]] == ["EXECUTED", "EXECUTED"]


def test_unknown_function_is_answered_without_asking_agentgate():
    llm = ScriptedChatModel.of(calls(("local__fail", {})), "ok", "done")
    gate = PolicyGate()
    graph, state = build(agent_workflow(), llm, gate)

    graph.invoke(state, CONFIG)

    assert llm.log[1]["messages"][-1].content == "There is no tool named 'local__fail'."
    assert gate.bodies == []


def test_last_turn_gets_no_tools_and_must_answer():
    echo = ("local__echo", {"text": "again"})
    llm = ScriptedChatModel.of(calls(echo), calls(echo), "final", "done")
    graph, state = build(agent_workflow(maxSteps=3), llm, PolicyGate())

    result = graph.invoke(state, CONFIG)

    assert result["helper"] == "final"
    last = llm.log[2]
    assert last["tools"] is None
    assert last["messages"][-1] == HumanMessage(FINAL_TURN_NOTE)


def test_tool_failure_is_reported_to_the_model():
    dsl = agent_workflow(tools=[{"server": "local", "tool": "fail", "permission": "AUTO"}])
    llm = ScriptedChatModel.of(calls(("local__fail", {})), "failed", "done")
    graph, state = build(dsl, llm, PolicyGate())

    result = graph.invoke(state, CONFIG)

    assert llm.log[1]["messages"][-1].content.startswith("The tool failed:")
    assert result["tool_results"][0]["status"] == "FAILED"


def test_resumed_execution_rebuilds_its_graph_from_saved_state():
    llm = ScriptedChatModel.of(calls(("local__add", {"a": 1, "b": 1})), "2", "done")
    gate = PolicyGate(add="APPROVAL_REQUIRED")
    checkpointer = InMemorySaver()
    graph, state = build(agent_workflow(), llm, gate, checkpointer)
    graph.invoke(state, CONFIG)

    # A fresh runner (e.g. after a restart) knows nothing but the checkpoint; the saved
    # workflow carries the tool schemas, so no catalog is needed.
    client = AgentGateClient(
        "http://agentgate", "runtime-agent", transport=httpx.MockTransport(gate)
    )
    compiler = WorkflowCompiler(lambda m, t: llm, client, mcp_servers={"local": ECHO_SERVER})
    restored = WorkflowRunner(compiler, checkpointer).graph_for_execution("exec-1")
    result = restored.invoke(Command(resume={"decision": "APPROVED"}), CONFIG)

    assert result["helper"] == "2"


def test_function_names_are_valid_and_unique():
    from app.dsl.schema import AgentToolSpec

    specs = [
        AgentToolSpec(server="my.server", tool="do it", permission="AUTO"),
        AgentToolSpec(server="my_server", tool="do_it", permission="AUTO"),
    ]
    assert function_names(specs) == ["my_server__do_it", "my_server__do_it_2"]


def compile_only(dsl: dict, servers=None):
    client = AgentGateClient("http://agentgate", "runtime-agent", "k")
    compiler = WorkflowCompiler(
        lambda m, t: None, client, mcp_servers=servers or {"local": ECHO_SERVER}
    )
    return compiler.compile(Workflow.model_validate(dsl))


def test_agent_must_be_resolved_for_the_run():
    dsl = agent_workflow()
    dsl["agents"] = {}
    with pytest.raises(CompileError, match="agent 'note-agent' was not resolved"):
        compile_only(dsl)


def test_pinned_version_must_match_the_resolved_one():
    dsl = agent_workflow()
    dsl["nodes"][1]["config"]["agentVersion"] = 2
    with pytest.raises(CompileError, match="needs agent 'note-agent' v2, got v3"):
        compile_only(dsl)


def test_tools_need_their_schema_and_a_known_server():
    with pytest.raises(CompileError, match="no schema for tool local/echo"):
        compile_only(agent_workflow())
    dsl = agent_workflow(tools=[{"server": "other", "tool": "x", "permission": "AUTO"}])
    with pytest.raises(CompileError, match="unknown MCP server 'other'"):
        compile_only(dsl)


def test_attaching_schemas_skips_blocked_tools_and_rejects_unknown_ones():
    workflow = attach_tool_schemas(Workflow.model_validate(agent_workflow()), CATALOG)
    tools = {t.tool: t for t in workflow.agents["note-agent"].tools}
    assert tools["echo"].description == "Return the text prefixed with 'echo: '."
    assert tools["fail"].input_schema is None

    dsl = agent_workflow(tools=[{"server": "local", "tool": "nope", "permission": "AUTO"}])
    with pytest.raises(CompileError, match="tool local/nope is not available"):
        attach_tool_schemas(Workflow.model_validate(dsl), CATALOG)


def test_agent_reference_rules_are_validated():
    dsl = agent_workflow()
    dsl["nodes"][1]["config"]["system"] = "inline"
    _, issues = validate_workflow(dsl)
    assert "come from the agent definition" in issues[0].message

    dsl = agent_workflow()
    dsl["nodes"][1]["config"] = {"prompt": "x", "agentVersion": 1}
    _, issues = validate_workflow(dsl)
    assert "agentVersion needs agentId" in issues[0].message


def test_agent_snapshot_ignores_fields_it_does_not_know():
    spec = agent_spec(createdAt="2026-10-08T00:00:00Z", owner="ops")
    dsl = {**agent_workflow(), "agents": {"note-agent": spec}}
    assert Workflow.model_validate(dsl).agents["note-agent"].version == 3


def test_saved_workflows_need_no_agents_to_validate():
    dsl = agent_workflow()
    del dsl["agents"]
    workflow, issues = validate_workflow(dsl)
    assert issues == []
    with pytest.raises(ValidationError):
        Workflow.model_validate({**dsl, "agents": {"x": {"version": 1}}})


def test_execution_with_an_unavailable_agent_tool_is_rejected():
    from fastapi.testclient import TestClient

    from app.executions import get_runner
    from app.main import app
    from app.tools_api import get_catalog

    down = ToolCatalog({"local": McpServerConfig(url="http://127.0.0.1:1/mcp")})
    app.dependency_overrides[get_catalog] = lambda: down
    app.dependency_overrides[get_runner] = lambda: None
    try:
        response = TestClient(app).post(
            "/runtime/executions", json={"task": "t", "workflow": agent_workflow()}
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 422
    assert "tool local/echo is not available" in response.json()["detail"]


# --- JSON tool calling (models without function calling) ---


def test_json_mode_reads_calls_from_the_reply_and_sends_results_as_text():
    llm = ScriptedChatModel.of(
        '```json\n{"tool": "local__echo", "arguments": {"text": "hi"}}\n```', "It said hi.", "done"
    )
    gate = PolicyGate()
    graph, state = build(agent_workflow(toolCalling="JSON"), llm, gate)

    result = graph.invoke(state, CONFIG)

    assert result["helper"] == "It said hi."
    first, second = llm.log[0], llm.log[1]
    assert first["tools"] is None  # nothing bound
    system = first["messages"][0].content
    assert "- local__echo: Return the text prefixed with 'echo: '." in system
    assert '{"tool": "<tool name>"' in system
    # The model sees its own JSON and the result as plain messages, never tool messages.
    assert not any(isinstance(m, ToolMessage) for m in second["messages"])
    assert second["messages"][-1] == HumanMessage("Result of local__echo:\necho: hi")
    assert gate.bodies[0]["action"] == "MCP:local:echo"
    assert result["tool_results"][0]["status"] == "EXECUTED"


def test_json_mode_calls_are_governed_like_native_ones():
    llm = ScriptedChatModel.of('{"tool": "local__add", "arguments": {"a": 1, "b": 2}}', "3", "done")
    graph, state = build(
        agent_workflow(toolCalling="JSON"), llm, PolicyGate(add="APPROVAL_REQUIRED")
    )

    graph.invoke(state, CONFIG)
    assert graph.get_state(CONFIG).interrupts[0].value["approval_id"] == 101
    result = graph.invoke(Command(resume={"decision": "APPROVED"}), CONFIG)

    assert result["helper"] == "3"
    assert llm.log[1]["messages"][-1].content == "Result of local__add:\n3"


def test_json_mode_reply_that_is_not_a_call_is_the_answer():
    llm = ScriptedChatModel.of('The result is {"total": 3}, no tool needed.', "done")
    graph, state = build(agent_workflow(toolCalling="JSON"), llm, PolicyGate())

    result = graph.invoke(state, CONFIG)

    assert result["helper"] == 'The result is {"total": 3}, no tool needed.'
    assert "tool_results" not in result or result["tool_results"] == []


def test_runtime_default_mode_applies_when_the_definition_has_none():
    llm = ScriptedChatModel.of('{"tool": "local__echo", "arguments": {"text": "x"}}', "ok", "done")
    workflow = attach_tool_schemas(Workflow.model_validate(agent_workflow()), CATALOG)
    client = AgentGateClient(
        "http://agentgate", "runtime-agent", transport=httpx.MockTransport(PolicyGate())
    )
    compiler = WorkflowCompiler(
        lambda m, t: llm, client, mcp_servers={"local": ECHO_SERVER}, tool_calling="JSON"
    )
    graph = compiler.compile(workflow, InMemorySaver())
    state = {"task": "t", "workflow": workflow.model_dump(mode="json", by_alias=True)}

    result = graph.invoke(state, CONFIG)

    assert result["helper"] == "ok"
    assert llm.log[0]["tools"] is None


# --- Output schema ---

SCHEMA = {
    "type": "object",
    "properties": {"total": {"type": "integer"}, "note": {"type": "string"}},
    "required": ["total"],
}


def test_answer_matching_the_schema_is_stored_as_compact_json():
    llm = ScriptedChatModel.of('Here:\n```json\n{"total": 5, "note": "ok"}\n```', "done")
    graph, state = build(agent_workflow(tools=[], outputSchema=SCHEMA), llm, PolicyGate())

    result = graph.invoke(state, CONFIG)

    assert result["helper"] == '{"total":5,"note":"ok"}'
    assert '"required": ["total"]' in llm.log[0]["messages"][0].content


def test_non_matching_answer_gets_one_repair_turn():
    llm = ScriptedChatModel.of('{"total": "five"}', '{"total": 5}', "done")
    graph, state = build(agent_workflow(outputSchema=SCHEMA), llm, PolicyGate())

    result = graph.invoke(state, CONFIG)

    assert result["helper"] == '{"total":5}'
    repair = llm.log[1]
    assert repair["tools"] is None  # answer turn, no tools
    assert repair["messages"][-1].content.startswith(
        "Your answer does not match the required format: total: 'five' is not of type 'integer'"
    )


def test_answer_still_not_matching_after_repair_fails_the_node():
    from app.nodes.agent import AgentOutputError

    llm = ScriptedChatModel.of("no json here", '{"note": "missing total"}')
    graph, state = build(agent_workflow(tools=[], outputSchema=SCHEMA), llm, PolicyGate())

    with pytest.raises(AgentOutputError, match="'total' is a required property"):
        graph.invoke(state, CONFIG)
    assert "not valid JSON" in llm.log[1]["messages"][-1].content
