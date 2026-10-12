"""Executions, model calls, governance decisions and tool runs as OpenTelemetry spans."""

import pytest
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from app import telemetry
from tests.fake_llm import ScriptedChatModel, calls
from tests.test_agent_node import PolicyGate, agent_outputs, agent_workflow, build

_exporter = InMemorySpanExporter()
_provider = TracerProvider()
_provider.add_span_processor(SimpleSpanProcessor(_exporter))
# The global provider can be set once per process; these tests are the only ones reading spans.
trace.set_tracer_provider(_provider)


@pytest.fixture
def spans():
    _exporter.clear()
    yield lambda: list(_exporter.get_finished_spans())
    telemetry._capture_content = False


def by_name(spans, prefix: str):
    return [s for s in spans if s.name.startswith(prefix)]


def named(spans, name: str):
    return [s for s in spans if s.name == name]


def test_an_agent_run_is_one_trace_of_model_governance_and_tool_spans(spans):
    reply = calls(("local__echo", {"text": "hi"}))
    reply.usage_metadata = {"input_tokens": 120, "output_tokens": 15, "total_tokens": 135}
    reply.response_metadata = {"model_name": "qwen3-32b"}
    llm = ScriptedChatModel.of(reply, "It said hi.", "summary")
    graph, state = build(agent_workflow(), llm, PolicyGate())

    agent_outputs(graph, state)

    finished = spans()
    [execution] = named(finished, "agentgate.execution")
    assert execution.attributes["agentgate.execution.id"] == "exec-1"
    assert execution.attributes["langfuse.session.id"] == "exec-1"
    assert execution.attributes["agentgate.workflow.id"] == "agentic"
    assert execution.attributes["agentgate.execution.outcome"] == "COMPLETED"
    # Spans made inside graph steps (run on worker threads) belong to the execution's trace,
    # including the MCP client's own spans under the tool run. (Listing the tool catalog before
    # the run is traced on its own.)
    ours = [s for s in finished if not s.name.startswith("MCP ")]
    assert {s.context.trace_id for s in ours} == {execution.context.trace_id}
    [tool] = by_name(finished, "tool helper:local/echo")
    mcp_call = named(finished, "MCP send tools/call echo")[0]
    assert mcp_call.parent.span_id == tool.context.span_id

    first_turn = by_name(finished, "chat")[0]
    assert first_turn.attributes["gen_ai.response.model"] == "qwen3-32b"
    assert first_turn.attributes["gen_ai.usage.input_tokens"] == 120
    assert first_turn.attributes["agentgate.agent.id"] == "note-agent"
    assert first_turn.attributes["langfuse.observation.type"] == "generation"
    assert first_turn.parent.span_id == execution.context.span_id

    [decision] = by_name(finished, "governance MCP:local:echo")
    assert decision.attributes["agentgate.decision.status"] == "ALLOWED"
    assert decision.attributes["agentgate.decision.basis"] == "POLICY"
    assert decision.attributes["agentgate.step"] == "helper.gate"

    assert tool.attributes["agentgate.tool.status"] == "EXECUTED"
    # No prompts or answers unless content capture is on.
    assert not any("langfuse.observation.input" in s.attributes for s in finished)


def test_a_resume_after_approval_joins_the_same_session(spans):
    llm = ScriptedChatModel.of(calls(("local__add", {"a": 2, "b": 3})), "2+3=5", "done")
    graph, state = build(agent_workflow(), llm, PolicyGate(add="APPROVAL_REQUIRED"))

    agent_outputs(graph, state, resume="APPROVED")

    finished = spans()
    [first] = named(finished, "agentgate.execution")
    [resumed] = named(finished, "agentgate.execution.resume")
    assert first.attributes["agentgate.execution.outcome"] == "WAITING_APPROVAL"
    assert first.attributes["agentgate.approval.waiting_id"] == 101
    assert resumed.attributes["agentgate.approval.decision"] == "APPROVED"
    assert resumed.attributes["session.id"] == first.attributes["session.id"]
    [decision] = by_name(finished, "governance MCP:local:add")
    assert decision.attributes["agentgate.decision.status"] == "APPROVAL_REQUIRED"
    assert decision.attributes["agentgate.approval.id"] == 101
    [tool] = by_name(finished, "tool helper:local/add")
    assert tool.context.trace_id == resumed.context.trace_id
    assert tool.attributes["agentgate.approval.id"] == 101


def test_content_is_exported_only_when_enabled(spans):
    telemetry._capture_content = True
    llm = ScriptedChatModel.of(calls(("local__echo", {"text": "secret"})), "done", "summary")
    graph, state = build(agent_workflow(), llm, PolicyGate())

    agent_outputs(graph, state)

    finished = spans()
    first_turn = by_name(finished, "chat")[0]
    assert "Task: add things" in first_turn.attributes["langfuse.observation.input"]
    [tool] = by_name(finished, "tool helper:local/echo")
    assert "secret" in tool.attributes["langfuse.observation.input"]
    assert tool.attributes["langfuse.observation.output"] == "echo: secret"


def test_tracing_stays_off_without_an_endpoint(monkeypatch):
    monkeypatch.delenv("OTEL_EXPORTER_OTLP_ENDPOINT", raising=False)
    monkeypatch.delenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", raising=False)

    assert telemetry.setup_tracing() is False
