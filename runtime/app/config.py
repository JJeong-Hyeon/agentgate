from functools import lru_cache
from typing import Literal

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    agentgate_base_url: str = "http://localhost:8080"
    # Agent that workflow-level tools and approvals are evaluated as; agent nodes use their own.
    agentgate_agent_id: str = "runtime-agent"
    # Only used without RUNTIME_TOKEN; with it, AgentGate trusts the runtime for any agent.
    agentgate_api_key: str = ""
    llm_base_url: str = "http://localhost:11434/v1"
    llm_api_key: str = ""
    llm_model: str = "qwen2.5:7b"
    llm_temperature: float = 0.0
    llm_timeout_seconds: float = 60.0
    # How agents call tools unless their definition says: "native" (OpenAI function calling)
    # or "json" (tools described in the prompt, calls read from the model's JSON reply).
    llm_tool_calling: Literal["native", "json"] = "native"
    # How much the model reasons before answering (OpenAI reasoning_effort: none, minimal, low,
    # medium, high), unless an agent's definition says. Empty → not sent; the model decides.
    # "none" turns off thinking in reasoning models such as qwen3 on Ollama (much faster).
    llm_reasoning_effort: Literal["", "none", "minimal", "low", "medium", "high"] = ""
    # Run every tool call of a model turn. Off: only the first, and the model decides the rest
    # after seeing its result (local models otherwise guess arguments that depend on it).
    llm_parallel_tool_calls: bool = False
    # Model calls sent to the model server at once; more wait here, so the server's own queue
    # does not eat into LLM_TIMEOUT_SECONDS. 0 → no limit. Match the server (OLLAMA_NUM_PARALLEL).
    llm_max_concurrency: int = Field(default=0, ge=0)
    # Empty → in-memory checkpoints (lost on restart).
    runtime_database_url: str = ""
    # Shared secret with AgentGate (X-Runtime-Token both ways): it authenticates resume requests
    # from AgentGate and this runtime's action evaluations and events. Empty → resume disabled.
    runtime_token: str = ""
    # Local development only: lets the runtime start, and answer anyone, without RUNTIME_TOKEN.
    runtime_allow_no_token: bool = False
    # JSON file with MCP servers ({"mcpServers": {...}}); empty → no MCP tools available.
    mcp_config_path: str = ""
    # JSON file of alternate LLM providers ({"gemini": {"base_url": "...", "api_key": "..."}});
    # an agent picks one with model "gemini@gemini-2.5-flash" instead of just a model name.
    # Empty → only the default LLM_BASE_URL/LLM_MODEL above is available.
    llm_providers_path: str = ""
    # Export prompts and answers on trace spans (OpenTelemetry, when an OTLP endpoint is set).
    otel_capture_content: bool = False


@lru_cache
def get_settings() -> Settings:
    return Settings()
