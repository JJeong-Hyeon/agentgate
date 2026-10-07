"""Agent nodes for the planner → researcher → reviewer graph.

Each factory takes a ChatModel from the LLM Gateway and returns a LangGraph node
function that reads and updates ResearchState.
"""

import re
from collections.abc import Callable

from langchain_core.language_models import BaseChatModel
from langchain_core.messages import HumanMessage, SystemMessage

from app.graph.state import ResearchState

Node = Callable[[ResearchState], ResearchState]

PLANNER_PROMPT = (
    "You are a planner. Break the user's task into 3-5 short, concrete research steps. "
    "Reply with a numbered list only."
)
RESEARCHER_PROMPT = (
    "You are a researcher. Follow the plan and write concise findings for the task. "
    "If reviewer feedback is given, address it."
)
REVIEWER_PROMPT = (
    "You are a strict reviewer. Check whether the findings fully answer the task. "
    "Start your reply with exactly 'VERDICT: APPROVE' or 'VERDICT: REVISE', "
    "then give brief feedback."
)

_VERDICT = re.compile(r"VERDICT:\s*(APPROVE|REVISE)", re.IGNORECASE)


def _ask(llm: BaseChatModel, system: str, user: str) -> str:
    reply = llm.invoke([SystemMessage(system), HumanMessage(user)])
    return str(reply.content).strip()


def parse_verdict(review: str) -> str:
    match = _VERDICT.search(review)
    # Unparseable reviews count as REVISE; the revision limit stops endless loops.
    return match.group(1).upper() if match else "REVISE"


def make_planner(llm: BaseChatModel) -> Node:
    def planner(state: ResearchState) -> ResearchState:
        return {"plan": _ask(llm, PLANNER_PROMPT, f"Task: {state['task']}")}

    return planner


def make_researcher(llm: BaseChatModel) -> Node:
    def researcher(state: ResearchState) -> ResearchState:
        user = f"Task: {state['task']}\n\nPlan:\n{state['plan']}"
        if state.get("review"):
            user += (
                f"\n\nPrevious findings:\n{state['findings']}"
                f"\n\nReviewer feedback:\n{state['review']}"
            )
        return {"findings": _ask(llm, RESEARCHER_PROMPT, user)}

    return researcher


def make_reviewer(llm: BaseChatModel) -> Node:
    def reviewer(state: ResearchState) -> ResearchState:
        user = f"Task: {state['task']}\n\nFindings:\n{state['findings']}"
        review = _ask(llm, REVIEWER_PROMPT, user)
        return {
            "review": review,
            "verdict": parse_verdict(review),
            "revisions": state.get("revisions", 0) + 1,
        }

    return reviewer
