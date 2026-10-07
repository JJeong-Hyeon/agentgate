import operator
from typing import Annotated, Literal, TypedDict


class ResearchState(TypedDict, total=False):
    task: str
    plan: str
    findings: str
    review: str
    verdict: Literal["APPROVE", "REVISE"]
    revisions: int
    tool_results: Annotated[list[dict], operator.add]
    # Gate result of the tool currently waiting for approval or execution.
    pending_tool: dict | None
