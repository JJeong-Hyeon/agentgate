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


def test_reasoning_effort_is_sent_only_when_set():
    def payload(model):
        return model._get_request_payload([("user", "hi")])

    assert "reasoning_effort" not in payload(create_chat_model(settings()))
    by_default = create_chat_model(settings(llm_reasoning_effort="none"))
    assert payload(by_default)["reasoning_effort"] == "none"
    # An agent's own setting wins over the runtime default.
    agent = create_chat_model(settings(llm_reasoning_effort="none"), reasoning_effort="high")
    assert payload(agent)["reasoning_effort"] == "high"


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


class _SlowChatServer:
    """OpenAI-compatible chat endpoint that answers slowly and records how many calls overlap."""

    def __init__(self, delay: float = 0.3):
        import json
        import threading
        import time
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

        self.active = 0
        self.peak = 0
        lock = threading.Lock()
        server = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def do_POST(self):
                self.rfile.read(int(self.headers["Content-Length"]))
                with lock:
                    server.active += 1
                    server.peak = max(server.peak, server.active)
                time.sleep(delay)
                with lock:
                    server.active -= 1
                body = json.dumps(
                    {
                        "id": "c",
                        "object": "chat.completion",
                        "created": 0,
                        "model": "m",
                        "choices": [
                            {
                                "index": 0,
                                "message": {"role": "assistant", "content": "ok"},
                                "finish_reason": "stop",
                            }
                        ],
                    }
                ).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.httpd.server_address[1]}/v1"
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def close(self):
        self.httpd.shutdown()


def _call_at_once(server: _SlowChatServer, calls: int, **overrides) -> list[str]:
    from concurrent.futures import ThreadPoolExecutor

    config = Settings(_env_file=None, llm_base_url=server.url, llm_model="m", **overrides)
    # Separate chat models (as separate agents get) still share the limit.
    models = [create_chat_model(config) for _ in range(calls)]
    with ThreadPoolExecutor(calls) as pool:
        return list(pool.map(lambda m: m.invoke("hi").content, models))


def test_calls_beyond_the_concurrency_limit_wait_their_turn():
    server = _SlowChatServer()
    try:
        answers = _call_at_once(server, 4, llm_max_concurrency=2, llm_timeout_seconds=0.5)
    finally:
        server.close()

    # Each call takes 0.3s, so queued calls would time out (0.5s) if waiting counted.
    assert answers == ["ok"] * 4
    assert server.peak == 2


def test_without_a_limit_calls_go_out_together():
    server = _SlowChatServer()
    try:
        _call_at_once(server, 4)
    finally:
        server.close()

    assert server.peak == 4


def test_concurrency_limit_cannot_be_negative():
    with pytest.raises(ValueError):
        settings(llm_max_concurrency=-1)
