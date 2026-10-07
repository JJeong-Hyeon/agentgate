from typing import Literal, TypedDict


class ResearchState(TypedDict, total=False):
    task: str
    plan: str
    findings: str
    review: str
    verdict: Literal["APPROVE", "REVISE"]
    revisions: int
