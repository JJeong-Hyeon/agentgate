from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    agentgate_base_url: str = "http://localhost:8080"
    agentgate_api_key: str = ""
    llm_base_url: str = "http://localhost:11434/v1"
    llm_api_key: str = ""
    llm_model: str = "qwen2.5:7b"
    llm_temperature: float = 0.0
    llm_timeout_seconds: float = 60.0
    # Empty → in-memory checkpoints (lost on restart).
    runtime_database_url: str = ""
    max_review_revisions: int = 1


@lru_cache
def get_settings() -> Settings:
    return Settings()
