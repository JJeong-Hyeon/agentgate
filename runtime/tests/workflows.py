"""Workflow DSL documents and helpers shared by runtime tests."""

import copy
import json
from pathlib import Path

import httpx
from langchain_core.language_models import FakeListChatModel
from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.checkpoint.memory import InMemorySaver

from app.dsl import Workflow
from app.dsl.compiler import WorkflowCompiler
from app.governance.agentgate_client import AgentGateClient
from tests.fakes import FakeAgentGate, FakeTarget

_RESEARCH = json.loads((Path(__file__).parent.parent / "examples" / "research.json").read_text())

SIMPLE = {
    "workflowId": "echo",
    "nodes": [
        {"id": "start", "type": "START"},
        {"id": "answer", "type": "LLM", "config": {"prompt": "{task}"}},
        {"id": "end", "type": "END"},
    ],
    "edges": [
        {"source": "start", "target": "answer"},
        {"source": "answer", "target": "end"},
    ],
}


def research(report_url: str = "http://hook/report") -> dict:
    """The research example (plan → findings → review → report) reporting to `report_url`."""
    workflow = copy.deepcopy(_RESEARCH)
    report = next(n for n in workflow["nodes"] if n["id"] == "report")
    report["config"]["url"] = report_url
    return workflow


def compiler(
    replies: list[str],
    gate: FakeAgentGate | None = None,
    target: FakeTarget | None = None,
) -> WorkflowCompiler:
    """Compiler whose LLM answers `replies` in call order and whose tools hit fakes."""
    llm = FakeListChatModel(responses=replies)
    gate_client = AgentGateClient(
        "http://agentgate", "runtime-agent", "k", httpx.MockTransport(gate or FakeAgentGate())
    )
    return WorkflowCompiler(
        lambda model, temperature: llm, gate_client, httpx.MockTransport(target or FakeTarget())
    )


def compile_graph(
    dsl: dict,
    replies: list[str],
    gate: FakeAgentGate | None = None,
    target: FakeTarget | None = None,
    checkpointer: BaseCheckpointSaver | None = None,
):
    return compiler(replies, gate, target).compile(
        Workflow.model_validate(dsl), checkpointer or InMemorySaver()
    )


def initial_state(dsl: dict, task: str = "t") -> dict:
    return {"task": task, "workflow": dsl, "revisions": {}}
