"""Alternate LLM providers (OpenAI, Gemini, ...) beyond the default LLM_BASE_URL/LLM_MODEL.

Registered in a JSON file (LLM_PROVIDERS_PATH), same shape as MCP_CONFIG_PATH's mcpServers:

    {"gemini": {"base_url": "https://generativelanguage.googleapis.com/v1beta/openai/",
                "api_key": "..."}}

An agent picks one by prefixing its model with "<provider>@": "gemini@gemini-2.5-flash". '@' is
deliberate -- it can't collide with an Ollama tag ("qwen3:14b") or an HF/vLLM id ("org/model"),
so a plain model name is never mistaken for a provider reference.
"""

import json
from pathlib import Path

from pydantic import BaseModel


class LlmProviderConfig(BaseModel):
    base_url: str
    api_key: str = ""


def load_llm_providers(path: str) -> dict[str, LlmProviderConfig]:
    if not path:
        return {}
    providers = json.loads(Path(path).read_text())
    return {name: LlmProviderConfig.model_validate(cfg) for name, cfg in providers.items()}


def resolve_model(
    model: str | None, providers: dict[str, LlmProviderConfig]
) -> tuple[str | None, LlmProviderConfig | None]:
    """(bare_model, provider) for "<provider>@<model>" naming a registered provider; otherwise
    (model, None) unchanged. A model with no '@' is a plain default-provider model name, same
    as before this existed. A misspelled provider prefix is passed through unchanged too -- the
    default provider then rejects "typo@model" as an unknown model, rather than this silently
    picking some other provider."""
    if model and "@" in model:
        name, _, bare = model.partition("@")
        provider = providers.get(name)
        if provider:
            return bare, provider
    return model, None
