"""LLM Gateway.

Ollama and vLLM both expose an OpenAI-compatible API, so switching between them
only requires a different base URL. Agent code depends on the returned
ChatModel, never on a specific LLM server.
"""

import httpx
from langchain_core.language_models import BaseChatModel
from langchain_openai import ChatOpenAI
from pydantic import BaseModel

from app.config import Settings

# Ollama ignores the key, but the OpenAI client requires a non-empty value.
_PLACEHOLDER_API_KEY = "not-needed"


def create_chat_model(
    settings: Settings,
    *,
    model: str | None = None,
    temperature: float | None = None,
) -> BaseChatModel:
    return ChatOpenAI(
        base_url=settings.llm_base_url,
        api_key=settings.llm_api_key or _PLACEHOLDER_API_KEY,
        model=model or settings.llm_model,
        temperature=settings.llm_temperature if temperature is None else temperature,
        timeout=settings.llm_timeout_seconds,
        max_retries=1,
    )


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
