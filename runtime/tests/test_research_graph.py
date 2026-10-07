import os
import uuid

import pytest
from langchain_core.language_models import FakeListChatModel
from langgraph.checkpoint.memory import InMemorySaver

from app.graph.research import build_research_graph
from app.nodes.agent import parse_verdict


def run(responses: list[str], max_revisions: int = 1, checkpointer=None):
    graph = build_research_graph(
        FakeListChatModel(responses=responses), checkpointer or InMemorySaver(), max_revisions
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
