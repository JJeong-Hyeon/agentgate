from contextlib import asynccontextmanager
from typing import Annotated

from fastapi import Depends, FastAPI, Response, status

from app import executions, tools_api, workflows
from app.config import Settings, get_settings
from app.dsl.compiler import WorkflowCompiler
from app.events import EventReporter
from app.governance.agentgate_client import AgentGateClient
from app.graph.checkpointer import open_checkpointer
from app.llm import LlmHealth, check_llm_health, create_chat_model
from app.runner import WorkflowRunner
from app.telemetry import setup_tracing
from app.tools.catalog import ToolCatalog
from app.tools.mcp import load_mcp_servers
from app.tools.registry import AgentGateServers, McpServerRegistry


def build_gate(settings: Settings) -> AgentGateClient:
    return AgentGateClient(
        settings.agentgate_base_url,
        settings.agentgate_agent_id,
        settings.agentgate_api_key,
        runtime_token=settings.runtime_token,
    )


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    if not settings.runtime_token and not settings.runtime_allow_no_token:
        raise RuntimeError(
            "RUNTIME_TOKEN is not set, so the runtime API would answer anyone. Set it to the same "
            "value as AgentGate's AGENTGATE_RUNTIME_TOKEN "
            "(local development only: RUNTIME_ALLOW_NO_TOKEN=true)."
        )
    setup_tracing(settings.otel_capture_content)
    registered = (
        AgentGateServers(settings.agentgate_base_url, settings.runtime_token)
        if settings.runtime_token
        else None
    )
    mcp_servers = McpServerRegistry(load_mcp_servers(settings.mcp_config_path), registered)
    app.state.catalog = ToolCatalog(mcp_servers)
    with open_checkpointer(settings.runtime_database_url) as checkpointer:
        compiler = WorkflowCompiler(
            lambda model, temperature, reasoning_effort: create_chat_model(
                settings, model=model, temperature=temperature, reasoning_effort=reasoning_effort
            ),
            build_gate(settings),
            mcp_servers=mcp_servers,
            tool_calling=settings.llm_tool_calling.upper(),
            parallel_tool_calls=settings.llm_parallel_tool_calls,
        )
        app.state.runner = WorkflowRunner(compiler, checkpointer)
        app.state.reporter = EventReporter(settings.agentgate_base_url, settings.runtime_token)
        yield


app = FastAPI(title="AgentGate Runtime", lifespan=lifespan)
# Health checks stay open; everything under /runtime is for AgentGate only.
_runtime_api = [Depends(executions.require_token_if_configured)]
app.include_router(executions.router, dependencies=_runtime_api)
app.include_router(workflows.router, dependencies=_runtime_api)
app.include_router(tools_api.router, dependencies=_runtime_api)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


@app.get("/llm/health")
async def llm_health(
    response: Response, settings: Annotated[Settings, Depends(get_settings)]
) -> LlmHealth:
    result = await check_llm_health(settings)
    if result.status != "UP":
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
    return result
