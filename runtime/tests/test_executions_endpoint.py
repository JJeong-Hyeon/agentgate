import httpx
import pytest
from fastapi.testclient import TestClient
from langchain_core.language_models import FakeListChatModel
from langgraph.checkpoint.memory import InMemorySaver

from app.config import Settings, get_settings
from app.dsl.compiler import WorkflowCompiler
from app.events import EventReporter
from app.executions import get_reporter, get_runner
from app.governance.agentgate_client import AgentGateClient
from app.graph.research import build_research_graph
from app.main import app
from app.runner import WorkflowRunner
from app.tools.http import HttpTool, HttpToolSpec
from tests.fakes import FakeAgentGate, FakeAgentGateEvents, FakeTarget

TOKEN = "runtime-secret"
events_sinks: list[FakeAgentGateEvents] = []


def make_client(report_tool=None) -> TestClient:
    checkpointer = InMemorySaver()
    graph = build_research_graph(
        FakeListChatModel(responses=["1. step", "findings", "VERDICT: APPROVE"]),
        checkpointer,
        report_tool=report_tool,
    )
    compiler = WorkflowCompiler(lambda model, temperature: FakeListChatModel(responses=["ok"]))
    runner = WorkflowRunner(graph, compiler, checkpointer)
    app.dependency_overrides[get_runner] = lambda: runner
    sink = FakeAgentGateEvents()
    app.dependency_overrides[get_reporter] = lambda: EventReporter(
        "http://agentgate", "t", httpx.MockTransport(sink)
    )
    events_sinks.append(sink)
    app.dependency_overrides[get_settings] = lambda: Settings(_env_file=None, runtime_token=TOKEN)
    return TestClient(app)


@pytest.fixture(autouse=True)
def clear_overrides():
    yield
    app.dependency_overrides.clear()


@pytest.fixture
def client():
    return make_client()


@pytest.fixture
def target():
    return FakeTarget()


@pytest.fixture
def approval_client(target):
    gate = AgentGateClient(
        "http://agentgate",
        "runtime-agent",
        "k",
        httpx.MockTransport(FakeAgentGate("APPROVAL_REQUIRED", "HIGH", 7)),
    )
    spec = HttpToolSpec(name="report", action="SEND_REPORT", url="http://hook/report")
    return make_client(HttpTool(spec, gate, httpx.MockTransport(target)))


def start(client) -> dict:
    return client.post("/runtime/executions", json={"task": "t"}).json()


def resume(client, execution_id, approval_id=7, decision="APPROVED", token=TOKEN):
    return client.post(
        f"/runtime/executions/{execution_id}/resume",
        json={"approvalId": approval_id, "decision": decision},
        headers={"X-Runtime-Token": token} if token else {},
    )


def test_create_execution_runs_graph(client):
    response = client.post("/runtime/executions", json={"task": "Compare Ollama and vLLM"})

    assert response.status_code == 201
    body = response.json()
    assert body["status"] == "COMPLETED"
    assert body["state"]["findings"] == "findings"
    assert body["state"]["verdict"] == "APPROVE"


def test_get_execution_returns_saved_state(client):
    execution_id = start(client)["execution_id"]

    response = client.get(f"/runtime/executions/{execution_id}")

    assert response.status_code == 200
    assert response.json()["state"]["plan"] == "1. step"


def test_get_unknown_execution_returns_404(client):
    assert client.get("/runtime/executions/unknown").status_code == 404


def test_empty_task_is_rejected(client):
    assert client.post("/runtime/executions", json={"task": ""}).status_code == 422


def test_execution_waits_for_approval(approval_client, target):
    body = start(approval_client)

    assert body["status"] == "WAITING_APPROVAL"
    assert body["waiting_approval_id"] == 7
    assert target.requests == []


def test_resume_approved_executes_tool(approval_client, target):
    execution_id = start(approval_client)["execution_id"]

    response = resume(approval_client, execution_id)

    assert response.status_code == 202
    assert response.json()["status"] == "RESUMING"
    after = approval_client.get(f"/runtime/executions/{execution_id}").json()
    assert after["status"] == "COMPLETED"
    assert after["state"]["tool_results"][0]["status"] == "EXECUTED"
    assert len(target.requests) == 1


def test_resume_rejected_skips_tool(approval_client, target):
    execution_id = start(approval_client)["execution_id"]

    resume(approval_client, execution_id, decision="REJECTED")

    after = approval_client.get(f"/runtime/executions/{execution_id}").json()
    assert after["state"]["tool_results"][0]["status"] == "REJECTED"
    assert target.requests == []


def test_resume_twice_is_conflict(approval_client, target):
    execution_id = start(approval_client)["execution_id"]
    resume(approval_client, execution_id)

    response = resume(approval_client, execution_id)

    assert response.status_code == 409
    assert len(target.requests) == 1


def test_resume_with_other_approval_id_is_conflict(approval_client):
    execution_id = start(approval_client)["execution_id"]

    assert resume(approval_client, execution_id, approval_id=99).status_code == 409


@pytest.mark.parametrize("token", ["wrong", None])
def test_resume_requires_runtime_token(approval_client, target, token):
    execution_id = start(approval_client)["execution_id"]

    assert resume(approval_client, execution_id, token=token).status_code == 401
    assert target.requests == []


def test_resume_disabled_without_configured_token(approval_client):
    execution_id = start(approval_client)["execution_id"]
    app.dependency_overrides[get_settings] = lambda: Settings(_env_file=None, runtime_token="")

    assert resume(approval_client, execution_id).status_code == 503


def test_resume_unknown_execution_is_404(approval_client):
    assert resume(approval_client, "unknown").status_code == 404


SIMPLE_WORKFLOW = {
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


def test_create_execution_from_workflow(client):
    response = client.post("/runtime/executions", json={"task": "t", "workflow": SIMPLE_WORKFLOW})

    assert response.status_code == 201
    body = response.json()
    assert body["status"] == "COMPLETED"
    assert body["state"]["answer"] == "ok"
    assert body["state"]["workflow"]["workflowId"] == "echo"


def test_workflow_execution_is_restored_by_id(client):
    execution_id = client.post(
        "/runtime/executions", json={"task": "t", "workflow": SIMPLE_WORKFLOW}
    ).json()["execution_id"]

    body = client.get(f"/runtime/executions/{execution_id}").json()

    assert body["state"]["answer"] == "ok"


def test_invalid_workflow_is_rejected_with_errors(client):
    broken = {**SIMPLE_WORKFLOW, "edges": SIMPLE_WORKFLOW["edges"][:1]}

    response = client.post("/runtime/executions", json={"task": "t", "workflow": broken})

    assert response.status_code == 422
    node_ids = {e["node_id"] for e in response.json()["detail"]["errors"]}
    assert {"answer", "end"} <= node_ids


def test_unsupported_workflow_is_rejected(client):
    workflow = {
        **SIMPLE_WORKFLOW,
        "nodes": [
            *SIMPLE_WORKFLOW["nodes"][:2],
            {"id": "ok", "type": "APPROVAL"},
            SIMPLE_WORKFLOW["nodes"][2],
        ],
        "edges": [
            {"source": "start", "target": "answer"},
            {"source": "answer", "target": "ok"},
            {"source": "ok", "target": "end"},
        ],
    }

    response = client.post("/runtime/executions", json={"task": "t", "workflow": workflow})

    assert response.status_code == 422
    assert "AgentGate" in response.json()["detail"]


def test_background_execution_with_caller_id(client):
    response = client.post(
        "/runtime/executions",
        json={
            "task": "t",
            "workflow": SIMPLE_WORKFLOW,
            "executionId": "exec-42",
            "background": True,
        },
    )

    assert response.status_code == 202
    assert response.json() == {
        "execution_id": "exec-42",
        "status": "RUNNING",
        "waiting_approval_id": None,
        "state": None,
    }
    # TestClient runs background tasks before returning.
    assert client.get("/runtime/executions/exec-42").json()["status"] == "COMPLETED"
    assert events_sinks[-1].types[-1] == "EXECUTION_COMPLETED"


def test_duplicate_execution_id_is_conflict(client):
    body = {"task": "t", "executionId": "exec-dup"}
    client.post("/runtime/executions", json=body)

    assert client.post("/runtime/executions", json=body).status_code == 409


def test_resume_reports_events(approval_client):
    execution_id = start(approval_client)["execution_id"]

    resume(approval_client, execution_id)

    assert events_sinks[-1].types[-1] == "EXECUTION_COMPLETED"
