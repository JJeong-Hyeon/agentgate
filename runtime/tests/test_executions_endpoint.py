import pytest
from fastapi.testclient import TestClient
from langchain_core.language_models import FakeListChatModel
from langgraph.checkpoint.memory import InMemorySaver

from app.executions import get_graph
from app.graph.research import build_research_graph
from app.main import app


@pytest.fixture
def client():
    graph = build_research_graph(
        FakeListChatModel(responses=["1. step", "findings", "VERDICT: APPROVE"]), InMemorySaver()
    )
    app.dependency_overrides[get_graph] = lambda: graph
    yield TestClient(app)
    app.dependency_overrides.clear()


def test_create_execution_runs_graph(client):
    response = client.post("/runtime/executions", json={"task": "Compare Ollama and vLLM"})

    assert response.status_code == 201
    body = response.json()
    assert body["status"] == "COMPLETED"
    assert body["state"]["findings"] == "findings"
    assert body["state"]["verdict"] == "APPROVE"


def test_get_execution_returns_saved_state(client):
    execution_id = client.post("/runtime/executions", json={"task": "t"}).json()["execution_id"]

    response = client.get(f"/runtime/executions/{execution_id}")

    assert response.status_code == 200
    assert response.json()["state"]["plan"] == "1. step"


def test_get_unknown_execution_returns_404(client):
    assert client.get("/runtime/executions/unknown").status_code == 404


def test_empty_task_is_rejected(client):
    assert client.post("/runtime/executions", json={"task": ""}).status_code == 422
