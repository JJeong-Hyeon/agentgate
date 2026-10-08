import httpx
import pytest
from langchain_core.language_models import FakeListChatModel
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.types import Command

from app.dsl import Workflow
from app.dsl.compiler import WorkflowCompiler
from app.events import EventReporter, run_graph, task_event
from tests.fakes import FakeAgentGate, FakeAgentGateEvents
from tests.workflows import SIMPLE, compile_graph, initial_state, research


def reporter(sink: FakeAgentGateEvents) -> EventReporter:
    return EventReporter("http://agentgate", "token", httpx.MockTransport(sink))


def test_task_event_mapping():
    started = task_event({"id": "t1", "name": "report.approval", "triggers": ("x",)})
    done = task_event(
        {"id": "t1", "name": "plan", "result": {"plan": "1."}, "error": None, "interrupts": []}
    )
    waiting = task_event(
        {
            "id": "t2",
            "name": "report.approval",
            "result": {},
            "error": None,
            "interrupts": [{"value": {"approval_id": 4}}],
        }
    )
    failed = task_event(
        {"id": "t3", "name": "plan", "result": None, "error": ValueError("boom"), "interrupts": []}
    )

    assert started == {
        "type": "NODE_STARTED",
        "nodeId": "report",
        "step": "report.approval",
        "taskId": "t1",
    }
    assert done["type"] == "NODE_COMPLETED" and done["output"] == '{"plan": "1."}'
    assert waiting["type"] == "NODE_WAITING" and waiting["approvalId"] == 4
    assert failed["type"] == "NODE_FAILED" and failed["error"] == "boom"


def test_reports_sent_with_token_to_execution_events():
    sink = FakeAgentGateEvents()

    reporter(sink).report("exec-1", {"type": "EXECUTION_COMPLETED"})

    request = sink.requests[0]
    assert request.url == "http://agentgate/api/v1/executions/exec-1/events"
    assert request.headers["X-Runtime-Token"] == "token"
    assert "at" in sink.events[0]


def test_disabled_reporter_sends_nothing():
    sink = FakeAgentGateEvents()

    EventReporter("", "", httpx.MockTransport(sink)).report("e", {"type": "EXECUTION_COMPLETED"})

    assert sink.requests == []


def test_failed_delivery_does_not_raise():
    def refuse(request):
        raise httpx.ConnectError("down")

    EventReporter("http://a", "t", httpx.MockTransport(refuse)).report("e", {"type": "X"})


def config(execution_id: str) -> dict:
    return {"configurable": {"thread_id": execution_id}}


def test_run_graph_reports_node_progress_then_completion():
    sink = FakeAgentGateEvents()
    dsl = research()
    graph = compile_graph(dsl, ["1. step", "findings", "VERDICT: APPROVE"])

    run_graph(graph, initial_state(dsl), config("e0"), reporter(sink))

    assert sink.types[-1] == "EXECUTION_COMPLETED"
    started = [e["step"] for e in sink.events if e["type"] == "NODE_STARTED"]
    assert started == ["plan", "findings", "review", "report", "report.execute"]


def test_run_graph_reports_waiting_and_resumed_steps():
    sink = FakeAgentGateEvents()
    dsl = research()
    graph = compile_graph(
        dsl, ["p", "f", "VERDICT: APPROVE"], FakeAgentGate("APPROVAL_REQUIRED", "HIGH", 9)
    )

    run_graph(graph, initial_state(dsl), config("e1"), reporter(sink))
    assert sink.types[-2:] == ["NODE_WAITING", "EXECUTION_WAITING"]
    assert sink.events[-1]["approvalId"] == 9

    sink.requests.clear()
    run_graph(graph, Command(resume={"decision": "APPROVED"}), config("e1"), reporter(sink))
    assert [(e["type"], e.get("step")) for e in sink.events] == [
        ("NODE_STARTED", "report.approval"),
        ("NODE_COMPLETED", "report.approval"),
        ("NODE_STARTED", "report.execute"),
        ("NODE_COMPLETED", "report.execute"),
        ("EXECUTION_COMPLETED", None),
    ]


def test_run_graph_reports_failure_and_reraises():
    sink = FakeAgentGateEvents()

    class Broken(FakeListChatModel):
        def invoke(self, *args, **kwargs):
            raise RuntimeError("llm down")

    llm = Broken(responses=["x"])
    graph = WorkflowCompiler(lambda model, temperature: llm).compile(
        Workflow.model_validate(SIMPLE), InMemorySaver()
    )

    with pytest.raises(RuntimeError):
        run_graph(graph, initial_state(SIMPLE), config("e2"), reporter(sink))

    # The failed step is reported before the execution fails.
    assert sink.types == ["NODE_STARTED", "NODE_FAILED", "EXECUTION_FAILED"]
    assert "llm down" in sink.events[1]["error"]
    assert "llm down" in sink.events[-1]["error"]
