from contextlib import asynccontextmanager
from typing import Annotated

from fastapi import Depends, FastAPI, Response, status

from app import executions, workflows
from app.config import Settings, get_settings
from app.governance.agentgate_client import AgentGateClient
from app.graph.checkpointer import open_checkpointer
from app.graph.research import build_research_graph
from app.llm import LlmHealth, check_llm_health, create_chat_model
from app.tools.http import HttpTool, HttpToolSpec


def build_report_tool(settings: Settings) -> HttpTool | None:
    if not settings.report_url:
        return None
    gate = AgentGateClient(
        settings.agentgate_base_url, settings.agentgate_agent_id, settings.agentgate_api_key
    )
    spec = HttpToolSpec(
        name="report",
        action=settings.report_action,
        url=settings.report_url,
        labels=settings.report_labels,
    )
    return HttpTool(spec, gate)


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    with open_checkpointer(settings.runtime_database_url) as checkpointer:
        app.state.graph = build_research_graph(
            create_chat_model(settings),
            checkpointer,
            settings.max_review_revisions,
            build_report_tool(settings),
        )
        yield


app = FastAPI(title="AgentGate Runtime", lifespan=lifespan)
app.include_router(executions.router)
app.include_router(workflows.router)


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
