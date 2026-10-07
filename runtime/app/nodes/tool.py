from collections.abc import Callable
from typing import Any

from langchain_core.runnables import RunnableConfig

from app.graph.state import ResearchState
from app.tools.http import HttpTool


def make_http_tool_node(
    tool: HttpTool, build_payload: Callable[[ResearchState], dict[str, Any]]
) -> Callable[[ResearchState, RunnableConfig], ResearchState]:
    def tool_node(state: ResearchState, config: RunnableConfig) -> ResearchState:
        # The LangGraph thread id is the execution id AgentGate links approvals to.
        execution_id = config.get("configurable", {}).get("thread_id")
        result = tool.run(build_payload(state), execution_id=execution_id)
        return {"tool_results": [result.model_dump()]}

    return tool_node
