import os

import httpx
import pytest
from langchain_openai import ChatOpenAI

from app.config import Settings
from app.llm import check_llm_health, create_chat_model


def settings(**overrides) -> Settings:
    return Settings(
        _env_file=None,
        llm_base_url="http://llm:11434/v1",
        llm_model="qwen2.5:7b",
        **overrides,
    )


def models_transport(*model_ids: str) -> httpx.MockTransport:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/v1/models"
        return httpx.Response(200, json={"data": [{"id": m} for m in model_ids]})

    return httpx.MockTransport(handler)


def test_create_chat_model_uses_settings():
    model = create_chat_model(settings(llm_temperature=0.2, llm_timeout_seconds=30))

    assert isinstance(model, ChatOpenAI)
    assert model.openai_api_base == "http://llm:11434/v1"
    assert model.model_name == "qwen2.5:7b"
    assert model.temperature == 0.2
    assert model.request_timeout == 30


def test_create_chat_model_overrides():
    model = create_chat_model(settings(), model="llama3.1:8b", temperature=0.7)

    assert model.model_name == "llama3.1:8b"
    assert model.temperature == 0.7


def test_create_chat_model_uses_placeholder_key_when_unset():
    model = create_chat_model(settings())

    assert model.openai_api_key.get_secret_value() == "not-needed"


@pytest.mark.anyio
async def test_health_up_when_model_available():
    result = await check_llm_health(settings(), models_transport("qwen2.5:7b", "llama3.1:8b"))

    assert result.status == "UP"
    assert result.model_available is True
    assert result.available_models == ["qwen2.5:7b", "llama3.1:8b"]


@pytest.mark.anyio
async def test_health_up_but_model_missing():
    result = await check_llm_health(settings(), models_transport("llama3.1:8b"))

    assert result.status == "UP"
    assert result.model_available is False


@pytest.mark.anyio
async def test_health_down_when_server_unreachable():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused")

    result = await check_llm_health(settings(), httpx.MockTransport(handler))

    assert result.status == "DOWN"
    assert "connection refused" in result.error


@pytest.mark.integration
@pytest.mark.skipif(not os.getenv("RUN_LLM_TESTS"), reason="RUN_LLM_TESTS not set")
def test_chat_with_real_llm():
    model = create_chat_model(Settings())

    reply = model.invoke("Reply with the single word: pong")

    assert reply.content.strip()
