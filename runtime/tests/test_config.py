from app.config import Settings


def test_settings_read_from_env(monkeypatch):
    monkeypatch.setenv("AGENTGATE_BASE_URL", "http://agentgate:8080")
    monkeypatch.setenv("LLM_MODEL", "qwen3:8b")

    settings = Settings()

    assert settings.agentgate_base_url == "http://agentgate:8080"
    assert settings.llm_model == "qwen3:8b"
