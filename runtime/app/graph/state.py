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
