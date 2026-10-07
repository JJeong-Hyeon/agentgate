from collections.abc import Callable
from typing import Any

from app.graph.state import ResearchState
from app.tools.http import HttpTool


def make_http_tool_node(
    tool: HttpTool, build_payload: Callable[[ResearchState], dict[str, Any]]
) -> Callable[[ResearchState], ResearchState]:
    def tool_node(state: ResearchState) -> ResearchState:
        result = tool.run(build_payload(state))
        return {"tool_results": [result.model_dump()]}

    return tool_node
