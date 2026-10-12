from functools import lru_cache
from typing import Literal

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
    # Empty → in-memory checkpoints (lost on restart).
    runtime_database_url: str = ""
    # Shared secret with AgentGate (X-Runtime-Token both ways): it authenticates resume requests
    # from AgentGate and this runtime's action evaluations and events. Empty → resume disabled.
    runtime_token: str = ""
    # JSON file with MCP servers ({"mcpServers": {...}}); empty → no MCP tools available.
    mcp_config_path: str = ""
    # Export prompts and answers on trace spans (OpenTelemetry, when an OTLP endpoint is set).
    otel_capture_content: bool = False


@lru_cache
def get_settings() -> Settings:
    return Settings()
