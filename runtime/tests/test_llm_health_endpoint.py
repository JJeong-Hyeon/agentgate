from fastapi.testclient import TestClient

import app.main as main
from app.llm import LlmHealth

client = TestClient(main.app)


def test_llm_health_returns_200_when_up(monkeypatch):
    async def fake_check(settings):
        return LlmHealth(status="UP", model="qwen2.5:7b", model_available=True)

    monkeypatch.setattr(main, "check_llm_health", fake_check)

    response = client.get("/llm/health")

    assert response.status_code == 200
    assert response.json()["model_available"] is True


def test_llm_health_returns_503_when_down(monkeypatch):
    async def fake_check(settings):
        return LlmHealth(status="DOWN", model="qwen2.5:7b", error="connection refused")

    monkeypatch.setattr(main, "check_llm_health", fake_check)

    response = client.get("/llm/health")

    assert response.status_code == 503
    assert response.json()["status"] == "DOWN"
