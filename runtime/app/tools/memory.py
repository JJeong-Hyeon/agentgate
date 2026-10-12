"""An agent's long-term memory as governed tools, plus recall at the start of a run.

Memories live in AgentGate, per agent. Searching is action MEMORY:SEARCH and saving MEMORY:SAVE,
evaluated for the agent like any tool call, so its definition's memory permissions and any
policies (risk, approval) apply.
"""

import json
from typing import Any

from langgraph.config import get_config

from app.governance.agentgate_client import AgentGateClient, AgentGateError
from app.tools.base import GovernedTool, ToolResult

SEARCH_ACTION = "MEMORY:SEARCH"
SAVE_ACTION = "MEMORY:SAVE"
SEARCH_LIMIT = 5
# Memories longer than AgentGate accepts are cut rather than refused.
MAX_MEMORY_CHARS = 4000
# A recalled memory shown to the model, and its prompt, are clipped to keep the context small.
MAX_RECALLED_CHARS = 500
MAX_QUERY_CHARS = 2000

SEARCH_FUNCTION = "memory_search"
SEARCH_DESCRIPTION = (
    "Search your long-term memory: what you saved in earlier runs and what your operators "
    "told you. Returns the most relevant memories."
)
SEARCH_PARAMETERS = {
    "type": "object",
    "properties": {"query": {"type": "string", "description": "What to look for."}},
    "required": ["query"],
}
SAVE_FUNCTION = "memory_save"
SAVE_DESCRIPTION = (
    "Save a fact to your long-term memory so later runs can recall it. Save durable, reusable "
    "facts (preferences, decisions, outcomes), one per call, written to stand on their own. "
    "Memories you were given are shown as [#id]. If the fact is already among them, do not save "
    "it again. If it updates or contradicts one, pass that id as `replaces` so the old one is "
    "no longer recalled."
)
SAVE_PARAMETERS = {
    "type": "object",
    "properties": {
        "content": {"type": "string", "description": "The fact to remember."},
        "replaces": {
            "type": "integer",
            "description": "Id of an earlier memory this fact updates or corrects.",
        },
    },
    "required": ["content"],
}


def _execution_id() -> str | None:
    try:
        return get_config().get("configurable", {}).get("thread_id")
    except RuntimeError:
        # Outside a graph run (e.g. called directly in a test).
        return None


class MemorySearchTool(GovernedTool):
    def __init__(self, name: str, agent_id: str, gate: AgentGateClient):
        super().__init__(name, SEARCH_ACTION, f"memory://{agent_id}", [], gate)
        self.agent_id = agent_id

    def execute(self, payload: dict[str, Any] | None, authorized: ToolResult) -> ToolResult:
        query = str((payload or {}).get("query") or "").strip()[:MAX_QUERY_CHARS]
        if not query:
            return ToolResult(**self._base(authorized), status="FAILED", error="query is required")
        try:
            found = self._gate.search_memories(self.agent_id, query, SEARCH_LIMIT)
        except AgentGateError as e:
            return ToolResult(**self._base(authorized), status="FAILED", error=str(e))
        memories = [{"id": m["id"], "content": m["content"]} for m in found]
        body = json.dumps(memories, ensure_ascii=False) if memories else "No memories found."
        return ToolResult(**self._base(authorized), status="EXECUTED", response_body=body)


class MemorySaveTool(GovernedTool):
    def __init__(self, name: str, agent_id: str, gate: AgentGateClient):
        super().__init__(name, SAVE_ACTION, f"memory://{agent_id}", [], gate)
        self.agent_id = agent_id

    def execute(self, payload: dict[str, Any] | None, authorized: ToolResult) -> ToolResult:
        content = str((payload or {}).get("content") or "").strip()[:MAX_MEMORY_CHARS]
        if not content:
            return ToolResult(
                **self._base(authorized), status="FAILED", error="content is required"
            )
        replaces = (payload or {}).get("replaces")
        if replaces is not None and not isinstance(replaces, int):
            try:
                replaces = int(replaces)
            except (TypeError, ValueError):
                return ToolResult(
                    **self._base(authorized), status="FAILED", error="replaces must be a memory id"
                )
        try:
            result = self._gate.save_memory(self.agent_id, content, _execution_id(), replaces)
        except AgentGateError as e:
            return ToolResult(**self._base(authorized), status="FAILED", error=str(e))
        return ToolResult(
            **self._base(authorized), status="EXECUTED", response_body=save_message(result)
        )


def save_message(result: dict) -> str:
    """What the agent is told about a save, so it can fix a repeat or an outdated memory."""
    memory_id = result["memory"]["id"]
    replaced = result.get("replaced")
    if result.get("duplicate"):
        message = f"Already remembered as memory #{memory_id}; nothing new was saved."
        if replaced is not None:
            message += f" Memory #{replaced} is replaced by it."
        return message
    message = f"Saved as memory #{memory_id}"
    message += f", replacing #{replaced}." if replaced is not None else "."
    similar = result.get("similar") or []
    if similar:
        listed = "; ".join(f"[#{m['id']}] {m['content'][:MAX_RECALLED_CHARS]}" for m in similar)
        message += (
            f" Similar memories: {listed}. If #{memory_id} repeats or updates one of them, call "
            f"memory_save again with the same content and replaces=<that id>."
        )
    return message


def recall(
    tool: MemorySearchTool,
    task: str,
    limit: int,
    execution_id: str | None,
    agent_version: int | None,
    delegated_by: str | None,
) -> tuple[list[str], ToolResult]:
    """Memories relevant to `task`, if AgentGate allows the search right away; never waits for
    an approval (the agent has not started, so there is nothing to pause)."""
    authorized = tool.authorize(
        execution_id,
        agent_id=tool.agent_id,
        agent_version=agent_version,
        delegated_by=delegated_by,
    )
    if authorized.status != "ALLOWED":
        return [], authorized
    try:
        found = tool._gate.search_memories(tool.agent_id, task[:MAX_QUERY_CHARS], limit)
    except AgentGateError as e:
        return [], ToolResult(**tool._base(authorized), status="FAILED", error=str(e))
    memories = [f"[#{m['id']}] {m['content'][:MAX_RECALLED_CHARS]}" for m in found]
    body = json.dumps(memories, ensure_ascii=False)
    return memories, ToolResult(**tool._base(authorized), status="EXECUTED", response_body=body)


def memory_prompt(memories: list[str]) -> str:
    lines = "\n".join(f"- {m}" for m in memories)
    return (
        "Relevant memories from earlier runs (they may be outdated; prefer the current task "
        f"when they conflict):\n{lines}"
    )
