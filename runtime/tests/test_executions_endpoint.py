import httpx
import pytest
from fastapi.testclient import TestClient
from langchain_core.language_models import FakeListChatModel
from langgraph.checkpoint.memory import InMemorySaver

from app.config import Settings, get_settings
from app.executions import get_graph
from app.governance.agentgate_client import AgentGateClient
from app.graph.research import build_research_graph
from app.main import app
from app.tools.http import HttpTool, HttpToolSpec
from tests.fakes import FakeAgentGate, FakeTarget

TOKEN = "runtime-secret"


def make_client(report_tool=None) -> TestClient:
    graph = build_research_graph(
        FakeListChatModel(responses=["1. step", "findings", "VERDICT: APPROVE"]),
        InMemorySaver(),
        report_tool=report_tool,
    )
    app.dependency_overrides[get_graph] = lambda: graph
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
