"""An agent whose model names a non-default provider is governed by AgentGate policy first."""

import json

import httpx
import pytest
from langgraph.checkpoint.memory import InMemorySaver

from app.config import Settings
from app.dsl.schema import AgentSpec
from app.governance.agentgate_client import AgentGateClient
from app.llm.policy import ModelNotAllowed, check_model_allowed
from tests.fake_llm import ScriptedChatModel
from tests.test_agent_node import CONFIG, PolicyGate
from tests.test_delegation import agent, build, delegate, team


@pytest.fixture
def providers(tmp_path, monkeypatch):
    path = tmp_path / "providers.json"
    path.write_text(json.dumps({"gemini": {"base_url": "http://gemini.invalid/v1"}}))
    settings = Settings(_env_file=None, llm_providers_path=str(path))
    monkeypatch.setattr("app.llm.policy.get_settings", lambda: settings)


def external_team():
    return team(research=agent("research", model="gemini@gemini-flash-latest"))


def test_a_blocked_provider_stops_the_agent_before_any_model_call(providers):
    llm = ScriptedChatModel.of(delegate("say hi"), "Could not delegate.")
    gate = PolicyGate(gemini="BLOCKED")
    graph, state, _ = build(external_team(), llm, gate, InMemorySaver())

    result = graph.invoke(state, CONFIG)

    assert len(llm.log) == 2  # only the supervisor thought; the worker never reached a model
    failure = llm.log[1]["messages"][-1].content
    assert "model provider 'gemini' is not allowed for agent 'research'" in failure
    assert result["helper"] == "Could not delegate."
    action = next(b for b in gate.bodies if b["action"] == "LLM:gemini")
    assert action["agentId"] == "research"
    assert action["delegatedBy"] == "lead"
    assert action["target"] == "llm://gemini"


def test_an_allowed_provider_runs_and_is_evaluated_once(providers):
    llm = ScriptedChatModel.of(delegate("say hi"), "worker says hi", "Team: hi")
    gate = PolicyGate()
    graph, state, _ = build(external_team(), llm, gate, InMemorySaver())

    result = graph.invoke(state, CONFIG)

    assert result["helper"] == "Team: hi"
    assert [b["action"] for b in gate.bodies] == ["AGENT:research", "LLM:gemini"]


def test_the_default_provider_needs_no_check(providers):
    llm = ScriptedChatModel.of(delegate("say hi"), "worker says hi", "Team: hi")
    gate = PolicyGate(gemini="BLOCKED")
    plain = team(research=agent("research", model="qwen3:14b"))
    graph, state, _ = build(plain, llm, gate, InMemorySaver())

    graph.invoke(state, CONFIG)

    assert [b["action"] for b in gate.bodies] == ["AGENT:research"]


def test_an_unreachable_agentgate_fails_closed(providers):
    spec = AgentSpec.model_validate(agent("research", model="gemini@gemini-flash-latest"))
    transport = httpx.MockTransport(lambda request: httpx.Response(500))
    down = AgentGateClient("http://agentgate", "runtime-agent", transport=transport)

    with pytest.raises(ModelNotAllowed, match="could not check model provider 'gemini'"):
        check_model_allowed(down, spec, "exec-1")
