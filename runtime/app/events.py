"""Reports execution progress to AgentGate, which stores it and streams it to the UI.

Delivery is best effort: a failed report is logged and never fails the execution.
"""

import json
import logging
from datetime import UTC, datetime
from typing import Any

import httpx
from langgraph.graph.state import CompiledStateGraph

from app.nodes.agent import agent_trace

log = logging.getLogger(__name__)

_MAX_OUTPUT_CHARS = 4000


class EventReporter:
    def __init__(
        self,
        base_url: str,
        token: str,
        transport: httpx.BaseTransport | None = None,
    ):
        self.enabled = bool(base_url and token)
        self._client = httpx.Client(
            base_url=base_url or "http://agentgate.disabled",
            headers={"X-Runtime-Token": token},
            transport=transport,
            timeout=5.0,
        )

    def report(self, execution_id: str, event: dict[str, Any]) -> None:
        if not self.enabled:
            return
        event = {**event, "at": datetime.now(UTC).isoformat()}
        try:
            response = self._client.post(f"/api/v1/executions/{execution_id}/events", json=event)
        except httpx.HTTPError as e:
            log.warning("Could not report %s for %s: %s", event["type"], execution_id, e)
            return
        if response.status_code == 404:
            # Started directly on the runtime, not through AgentGate; nobody is listening.
            log.debug("AgentGate does not track execution %s", execution_id)
        elif not response.is_success:
            log.warning(
                "AgentGate rejected %s for %s: %s",
                event["type"],
                execution_id,
                response.status_code,
            )


def task_event(task: dict[str, Any]) -> dict[str, Any]:
    """Map a LangGraph `tasks` stream item to an execution event.

    Sub-steps of a tool are named `<nodeId>.<step>`; events carry the DSL node id.
    """
    name = task["name"]
    event = {"nodeId": name.split(".")[0], "step": name, "taskId": task["id"]}
    if "triggers" in task:
        return {**event, "type": "NODE_STARTED"}
    if task.get("error"):
        return {**event, "type": "NODE_FAILED", "error": str(task["error"])}
    if task.get("interrupts"):
        value = task["interrupts"][0]["value"]
        return {**event, "type": "NODE_WAITING", "approvalId": value.get("approval_id")}
    result = task.get("result")
    if isinstance(result, dict) and "agent_runs" in result:
        # Agent steps report a summary instead of the whole conversation.
        trace = agent_trace(name, result)
        result = {"agent": trace} if trace else {}
    output = json.dumps(result, default=str, ensure_ascii=False)
    return {**event, "type": "NODE_COMPLETED", "output": output[:_MAX_OUTPUT_CHARS]}


def run_graph(
    graph: CompiledStateGraph, graph_input: Any, config: dict, reporter: EventReporter
) -> None:
    """Run (or resume) until the graph finishes or pauses, reporting each step."""
    execution_id = config["configurable"]["thread_id"]
    try:
        for task in graph.stream(graph_input, config, stream_mode="tasks"):
            reporter.report(execution_id, task_event(task))
    except Exception as e:
        reporter.report(execution_id, {"type": "EXECUTION_FAILED", "error": str(e)})
        raise

    snapshot = graph.get_state(config)
    if snapshot.interrupts:
        approval_id = snapshot.interrupts[0].value.get("approval_id")
        reporter.report(execution_id, {"type": "EXECUTION_WAITING", "approvalId": approval_id})
    elif not snapshot.next:
        reporter.report(execution_id, {"type": "EXECUTION_COMPLETED"})
