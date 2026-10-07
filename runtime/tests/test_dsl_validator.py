import copy
import json
from pathlib import Path

import pytest

from app.dsl import validate_workflow

EXAMPLE = json.loads((Path(__file__).parent.parent / "examples" / "research.json").read_text())


def minimal(*middle: dict, edges: list[dict] | None = None) -> dict:
    nodes = [{"id": "start", "type": "START"}, *middle, {"id": "end", "type": "END"}]
    if edges is None:
        chain = [n["id"] for n in nodes]
        edges = [{"source": a, "target": b} for a, b in zip(chain, chain[1:], strict=False)]
    return {"workflowId": "wf", "nodes": nodes, "edges": edges}


def llm(node_id: str, prompt: str = "Task: {task}", node_type: str = "LLM") -> dict:
    return {"id": node_id, "type": node_type, "config": {"prompt": prompt}}


def messages(data: dict) -> list[str]:
    _, issues = validate_workflow(data)
    return [f"{i.node_id}: {i.message}" if i.node_id else i.message for i in issues]


def test_research_example_is_valid():
    workflow, issues = validate_workflow(EXAMPLE)

    assert issues == []
    assert workflow.workflow_id == "research"
    assert [n.type for n in workflow.nodes] == [
        "START",
        "AGENT",
        "AGENT",
        "REVIEWER",
        "HTTP_TOOL",
        "END",
    ]


def test_minimal_linear_workflow_is_valid():
    assert messages(minimal(llm("answer"))) == []


def test_schema_errors_carry_path():
    data = minimal(llm("answer"))
    data["nodes"][1]["type"] = "MAGIC"

    _, issues = validate_workflow(data)

    assert issues[0].path.startswith("nodes.1")


def test_unknown_config_field_is_rejected():
    data = minimal(llm("answer"))
    data["nodes"][1]["config"]["temprature"] = 0.2

    assert messages(data)


@pytest.mark.parametrize("node_id", ["1abc", "has-dash", "task"])
def test_bad_or_reserved_node_ids(node_id):
    assert messages(minimal(llm(node_id)))


def test_duplicate_node_ids():
    data = minimal(llm("a"), llm("a"))

    assert "a: Duplicate node id 'a'" in messages(data)


def test_edge_to_unknown_node():
    data = minimal(llm("a"))
    data["edges"].append({"source": "a", "target": "ghost"})

    assert "Unknown node 'ghost'" in messages(data)


def test_requires_single_start_and_an_end():
    data = minimal(llm("a"))
    data["nodes"] = [n for n in data["nodes"] if n["type"] != "END"]
    data["edges"] = [e for e in data["edges"] if e["target"] != "end"]

    assert "At least one END node is required" in messages(data)


def test_start_needs_exactly_one_outgoing_edge():
    data = minimal(
        llm("a"),
        llm("b"),
        edges=[
            {"source": "start", "target": "a"},
            {"source": "start", "target": "b"},
            {"source": "a", "target": "end"},
            {"source": "b", "target": "end"},
        ],
    )

    assert "start: START must have exactly one outgoing edge" in messages(data)


def test_parallel_fan_out_from_regular_node_is_valid():
    data = minimal(
        llm("a"),
        llm("b"),
        llm("c"),
        edges=[
            {"source": "start", "target": "a"},
            {"source": "a", "target": "b"},
            {"source": "a", "target": "c"},
            {"source": "b", "target": "end"},
            {"source": "c", "target": "end"},
        ],
    )

    assert messages(data) == []


def test_unreachable_node():
    data = minimal(llm("a"))
    data["nodes"].insert(2, llm("orphan"))
    data["edges"].append({"source": "orphan", "target": "end"})

    assert "orphan: Not reachable from START" in messages(data)


def test_dead_end_node():
    data = minimal(llm("a"))
    data["nodes"].insert(2, llm("stuck"))
    data["edges"].append({"source": "a", "target": "stuck"})

    assert "stuck: No path to an END node" in messages(data)


def test_router_edges_must_match_routes():
    router = {
        "id": "route",
        "type": "ROUTER",
        "config": {"prompt": "{task}", "routes": ["db", "web"]},
    }
    data = minimal(
        router,
        llm("a"),
        edges=[
            {"source": "start", "target": "route"},
            {"source": "route", "target": "a", "label": "db"},
            {"source": "a", "target": "end"},
        ],
    )

    assert "route: Outgoing edge labels must be ['db', 'web'], got ['db']" in messages(data)


def test_regular_node_edges_must_not_have_labels():
    data = minimal(llm("a"))
    data["edges"][1]["label"] = "x"

    assert "a: Edges from LLM must not have labels" in messages(data)


def test_condition_needs_case_and_default_edges():
    cond = {
        "id": "check",
        "type": "CONDITION",
        "config": {"key": "a", "cases": {"yes": "Y"}, "default": "no"},
    }
    data = minimal(
        llm("a"),
        cond,
        edges=[
            {"source": "start", "target": "a"},
            {"source": "a", "target": "check"},
            {"source": "check", "target": "end", "label": "yes"},
            {"source": "check", "target": "end", "label": "no"},
        ],
    )

    assert messages(data) == []


def test_cycle_without_reviewer_is_rejected():
    data = minimal(llm("a"), llm("b"))
    data["edges"].append({"source": "b", "target": "a"})

    assert any("Cycle without a REVIEWER" in m for m in messages(data))


def test_cycle_through_reviewer_is_allowed():
    assert messages(EXAMPLE) == []


def test_unknown_prompt_variable():
    data = minimal(llm("a", "Use {ghost} and {task}"))

    assert "a: Unknown variable '{ghost}'" in messages(data)


def test_escaped_braces_are_not_variables():
    assert messages(minimal(llm("a", 'Reply as JSON: {{"ok": true}} for {task}'))) == []


def test_broken_template():
    assert any("Invalid template" in m for m in messages(minimal(llm("a", "oops {task"))))


def test_http_tool_payload_keys_must_exist():
    data = copy.deepcopy(EXAMPLE)
    data["nodes"][4]["config"]["payloadKeys"].append("secret")

    assert "report: Unknown state key 'secret'" in messages(data)
