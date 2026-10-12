"""Agents recalling and saving long-term memories, governed by AgentGate."""

import json

import httpx
from langchain_core.messages import SystemMessage, ToolMessage

from app.governance.agentgate_client import AgentGateClient, AgentGateError
from app.nodes.agent import agent_trace
from tests.fake_llm import ScriptedChatModel, calls
from tests.test_agent_node import CONFIG, PolicyGate, agent_workflow, build


class MemoryGate(PolicyGate):
    """AgentGate stand-in that also stores and searches memories."""

    def __init__(self, memories=(), **decisions: str):
        super().__init__(**decisions)
        self.memories = list(memories)
        self.saved: list[dict] = []
        self.searches: list[dict] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path == "/api/v1/runtime/memories/search":
            body = json.loads(request.content)
            self.searches.append(body)
            found = [{"id": i, "content": m} for i, m in enumerate(self.memories[: body["limit"]])]
            return httpx.Response(200, json=found)
        if path == "/api/v1/runtime/memories":
            body = json.loads(request.content)
            self.saved.append(body)
            return httpx.Response(201, json={"id": 7, "content": body["content"]})
        return super().__call__(request)


def memory(read="AUTO", write="AUTO", recall=0) -> dict:
    return {"memory": {"read": read, "write": write, "recall": recall}, "tools": []}


def tool_names(llm: ScriptedChatModel, turn: int = 0) -> list[str]:
    return [t["function"]["name"] for t in llm.log[turn]["tools"] or []]


def test_recall_gives_the_agent_relevant_memories_before_its_first_turn():
    llm = ScriptedChatModel.of("Done.", "summary")
    gate = MemoryGate(["Kim likes iced lattes", "Refunds within 7 days"])
    graph, state = build(agent_workflow(**memory(recall=2)), llm, gate)

    result = graph.invoke(state, CONFIG)

    system = llm.log[0]["messages"][0]
    assert isinstance(system, SystemMessage)
    assert system.content.startswith("You are careful.")
    assert "- Kim likes iced lattes\n- Refunds within 7 days" in system.content
    [search] = gate.searches
    assert search == {"agentId": "note-agent", "query": "Task: add things", "limit": 2}
    [evaluation] = gate.bodies
    assert evaluation["action"] == "MEMORY:SEARCH"
    assert evaluation["agentVersion"] == 3
    assert evaluation["executionId"] == "exec-1"
    [record] = result["tool_results"]
    assert record["tool"] == "helper:memory/recall"
    assert record["status"] == "EXECUTED"


def test_recall_is_skipped_when_agentgate_does_not_allow_it_right_away():
    llm = ScriptedChatModel.of("Done.", "summary")
    gate = MemoryGate(["secret"], SEARCH="BLOCKED")
    graph, state = build(agent_workflow(**memory(recall=3)), llm, gate)

    result = graph.invoke(state, CONFIG)

    assert "secret" not in llm.log[0]["messages"][0].content
    assert gate.searches == []
    assert result["tool_results"][0]["status"] == "BLOCKED"


def test_start_trace_shows_the_recalled_memories():
    llm = ScriptedChatModel.of("Done.", "summary")
    graph, state = build(agent_workflow(**memory(recall=1)), llm, MemoryGate(["Kim likes lattes"]))

    traces = []
    for chunk in graph.stream(state, CONFIG, stream_mode="updates"):
        for step, update in chunk.items():
            if step == "helper":
                traces.append(agent_trace(step, update))

    assert traces[0]["kind"] == "start"
    assert traces[0]["memories"] == ["Kim likes lattes"]
    assert traces[0]["memory_status"] == "EXECUTED"


def test_memory_tools_follow_the_definitions_permissions():
    for perms, expected in [
        (memory(), ["memory_search", "memory_save"]),
        (memory(write="BLOCKED"), ["memory_search"]),
        (memory(read="BLOCKED", write="APPROVAL"), ["memory_save"]),
        ({"tools": []}, []),
    ]:
        llm = ScriptedChatModel.of("Done.", "summary")
        graph, state = build(agent_workflow(**perms), llm, MemoryGate())
        graph.invoke(state, CONFIG)
        assert tool_names(llm) == expected, perms


def test_the_agent_saves_a_memory_through_agentgate():
    llm = ScriptedChatModel.of(
        calls(("memory_save", {"content": "Kim prefers email"})), "Noted.", "summary"
    )
    gate = MemoryGate()
    graph, state = build(agent_workflow(**memory()), llm, gate)

    result = graph.invoke(state, CONFIG)

    assert [b["action"] for b in gate.bodies] == ["MEMORY:SAVE"]
    assert gate.saved == [
        {"agentId": "note-agent", "content": "Kim prefers email", "executionId": "exec-1"}
    ]
    told = llm.log[1]["messages"][-1]
    assert isinstance(told, ToolMessage)
    assert told.content == "Saved as memory #7."
    assert result["tool_results"][0]["tool"] == "helper:memory/save"


def test_the_agent_searches_its_memory():
    llm = ScriptedChatModel.of(calls(("memory_search", {"query": "Kim"})), "Lattes.", "summary")
    graph, state = build(agent_workflow(**memory()), llm, MemoryGate(["Kim likes lattes"]))

    graph.invoke(state, CONFIG)

    assert json.loads(llm.log[1]["messages"][-1].content) == ["Kim likes lattes"]


def test_saving_waits_for_approval_when_required():
    llm = ScriptedChatModel.of(calls(("memory_save", {"content": "x"})), "ok", "summary")
    gate = MemoryGate(SAVE="APPROVAL_REQUIRED")
    graph, state = build(agent_workflow(**memory(write="APPROVAL")), llm, gate)

    graph.invoke(state, CONFIG)

    assert graph.get_state(CONFIG).next == ("helper.approval",)
    assert gate.saved == []


def test_client_raises_on_memory_errors():
    client = AgentGateClient(
        "http://agentgate",
        "runtime-agent",
        transport=httpx.MockTransport(lambda r: httpx.Response(401, json={})),
        runtime_token="t",
    )
    for call in (lambda: client.save_memory("a", "x"), lambda: client.search_memories("a", "q")):
        try:
            call()
        except AgentGateError as e:
            assert "401" in str(e)
        else:
            raise AssertionError("expected AgentGateError")
