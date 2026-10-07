from langchain_core.language_models import BaseChatModel
from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph

from app.graph.state import ResearchState
from app.nodes.agent import make_planner, make_researcher, make_reviewer
from app.nodes.tool import make_http_tool_node
from app.tools.http import HttpTool


def _report_payload(state: ResearchState) -> dict:
    return {"task": state["task"], "findings": state["findings"], "review": state["review"]}


def build_research_graph(
    llm: BaseChatModel,
    checkpointer: BaseCheckpointSaver | None = None,
    max_revisions: int = 1,
    report_tool: HttpTool | None = None,
) -> CompiledStateGraph:
    def after_review(state: ResearchState) -> str:
        # revisions counts reviews done; the first review is not a revision.
        if state["verdict"] == "REVISE" and state["revisions"] <= max_revisions:
            return "researcher"
        if state["verdict"] == "APPROVE" and report_tool:
            return "report"
        return END

    graph = StateGraph(ResearchState)
    graph.add_node("planner", make_planner(llm))
    graph.add_node("researcher", make_researcher(llm))
    graph.add_node("reviewer", make_reviewer(llm))
    graph.add_edge(START, "planner")
    graph.add_edge("planner", "researcher")
    graph.add_edge("researcher", "reviewer")

    destinations = ["researcher", END]
    if report_tool:
        graph.add_node("report", make_http_tool_node(report_tool, _report_payload))
        graph.add_edge("report", END)
        destinations.append("report")
    graph.add_conditional_edges("reviewer", after_review, destinations)
    return graph.compile(checkpointer=checkpointer)
