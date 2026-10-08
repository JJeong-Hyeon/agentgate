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
    registered = (
        AgentGateServers(settings.agentgate_base_url, settings.runtime_token)
        if settings.runtime_token
        else None
    )
    mcp_servers = McpServerRegistry(load_mcp_servers(settings.mcp_config_path), registered)
    app.state.catalog = ToolCatalog(mcp_servers)
    with open_checkpointer(settings.runtime_database_url) as checkpointer:
        compiler = WorkflowCompiler(
            lambda model, temperature: create_chat_model(
                settings, model=model, temperature=temperature
            ),
            build_gate(settings),
            mcp_servers=mcp_servers,
            tool_calling=settings.llm_tool_calling.upper(),
        )
        app.state.runner = WorkflowRunner(compiler, checkpointer)
        app.state.reporter = EventReporter(settings.agentgate_base_url, settings.runtime_token)
        yield


app = FastAPI(title="AgentGate Runtime", lifespan=lifespan)
app.include_router(executions.router)
app.include_router(workflows.router)
app.include_router(tools_api.router)


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
