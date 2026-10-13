"""LLM Gateway.

Ollama and vLLM both expose an OpenAI-compatible API, so switching between them
only requires a different base URL. Agent code depends on the returned
ChatModel, never on a specific LLM server.
"""

import threading
from contextlib import nullcontext
from functools import lru_cache

import httpx
from langchain_core.language_models import BaseChatModel
from langchain_openai import ChatOpenAI
from pydantic import BaseModel

from app.config import Settings
from app.llm.providers import load_llm_providers, resolve_model

# Ollama ignores the key, but the OpenAI client requires a non-empty value.
_PLACEHOLDER_API_KEY = "not-needed"
# How long a call may wait for its turn under LLM_MAX_CONCURRENCY before it fails.
_QUEUE_TIMEOUT_SECONDS = 900.0


def create_chat_model(
    settings: Settings,
    *,
    model: str | None = None,
    temperature: float | None = None,
    reasoning_effort: str | None = None,
) -> BaseChatModel:
    effort = reasoning_effort or settings.llm_reasoning_effort or None
    limit = settings.llm_max_concurrency
    bare_model, provider = resolve_model(model, _providers(settings.llm_providers_path))
    # A non-default provider is reached directly: LLM_MAX_CONCURRENCY and its connection pool
    # are sized for the default model server and must not also throttle a different one.
    return ChatOpenAI(
        base_url=provider.base_url if provider else settings.llm_base_url,
        api_key=(provider.api_key if provider else settings.llm_api_key) or _PLACEHOLDER_API_KEY,
        model=bare_model or settings.llm_model,
        temperature=settings.llm_temperature if temperature is None else temperature,
        # The timeout counts from sending the call; waiting for a free slot has its own limit.
        timeout=httpx.Timeout(settings.llm_timeout_seconds, pool=_QUEUE_TIMEOUT_SECONDS)
        if limit and not provider
        else settings.llm_timeout_seconds,
        http_client=_limited_client(limit) if limit and not provider else None,
        max_retries=1,
        reasoning_effort=effort,
    )


@lru_cache
def _providers(path: str) -> dict:
    return load_llm_providers(path)


@lru_cache
def _limited_client(limit: int) -> httpx.Client:
    """One connection pool for every chat model: at most `limit` calls reach the model server at
    once, the rest wait for a free connection. This is the actual enforcement; llm_slot() below
    is a same-sized semaphore callers can also hold, only so they can time the wait (the pool
    itself does not expose how long a call sat queued)."""
    return httpx.Client(limits=httpx.Limits(max_connections=limit, max_keepalive_connections=limit))


def llm_slot(settings: Settings):
    """Context manager held for the duration of one model call, sized like the connection pool
    above. A caller that holds this while invoking the model can time its own wait instead of
    that time hiding inside the pool's unmeasured queue; it does not replace the pool limit."""
    limit = settings.llm_max_concurrency
    return _semaphore(limit) if limit else nullcontext()


@lru_cache
def _semaphore(limit: int) -> threading.Semaphore:
    return threading.Semaphore(limit)


class LlmHealth(BaseModel):
    status: str
    model: str
    model_available: bool = False
    available_models: list[str] = []
    error: str | None = None


async def check_llm_health(
    settings: Settings, transport: httpx.AsyncBaseTransport | None = None
) -> LlmHealth:
    url = f"{settings.llm_base_url.rstrip('/')}/models"
    headers = {"Authorization": f"Bearer {settings.llm_api_key}"} if settings.llm_api_key else {}
    try:
        async with httpx.AsyncClient(transport=transport, timeout=5.0) as client:
            response = await client.get(url, headers=headers)
            response.raise_for_status()
    except httpx.HTTPError as e:
        return LlmHealth(status="DOWN", model=settings.llm_model, error=str(e) or type(e).__name__)

    models = [m["id"] for m in response.json().get("data", [])]
    return LlmHealth(
        status="UP",
        model=settings.llm_model,
        model_available=settings.llm_model in models,
        available_models=models,
    )
