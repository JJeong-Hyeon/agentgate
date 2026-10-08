"""Compile a validated Workflow DSL document into a LangGraph StateGraph.

State keys: `task` (input), `workflow` (the DSL itself, so an execution can be restored
from its checkpoint), one key per output node id, plus the runtime's bookkeeping keys.
"""

import operator
import re
from collections import defaultdict
from collections.abc import Callable
from typing import Annotated, Any, TypedDict

import httpx
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import HumanMessage, SystemMessage
from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph

from app.dsl.schema import (
    OUTPUT_TYPES,
    AgentNode,
    ApprovalNode,
    ConditionNode,
    HttpToolNode,
    LlmConfig,
    LlmNode,
    McpToolNode,
    ReviewerNode,
    RouterNode,
    Workflow,
)
from app.governance.agentgate_client import AgentGateClient
from app.nodes.agent import AgentTool, add_agent, function_names
from app.nodes.approval import add_approval
from app.nodes.tool import add_tool
from app.tools.http import HttpTool, HttpToolSpec
from app.tools.mcp import McpServerConfig, McpTool
from app.tools.registry import McpServerRegistry

# (model, temperature) → chat model; None keeps the runtime default.
LlmFactory = Callable[[str | None, float | None], BaseChatModel]

VERDICT_INSTRUCTION = "Start your reply with exactly 'VERDICT: APPROVE' or 'VERDICT: REVISE'."
_VERDICT = re.compile(r"VERDICT:\s*(APPROVE|REVISE)", re.IGNORECASE)


class CompileError(Exception):
    pass


def _merge(left: dict, right: dict) -> dict:
    return {**left, **right}


def _state_schema(workflow: Workflow) -> type:
    fields: dict[str, Any] = {
        "task": str,
        "workflow": dict,
        "tool_results": Annotated[list[dict], operator.add],
        "pending_tool": dict | None,
        # reviewer id → number of reviews done
        "revisions": Annotated[dict[str, int], _merge],
        # agent node id → its conversation and tool-call bookkeeping
        "agent_runs": Annotated[dict[str, dict], _merge],
        # set when a denied tool or approval stopped the execution: {node, status, reason}
        "stopped": dict | None,
    }
    for node in workflow.nodes:
        if node.type in OUTPUT_TYPES:
            fields[node.id] = str
    return TypedDict("WorkflowState", fields, total=False)


def render(template: str, state: dict) -> str:
    # Validation guarantees every variable is a known key; ones not produced yet are empty.
    values = defaultdict(str, {k: v for k, v in state.items() if isinstance(v, str)})
    return template.format_map(values)


def parse_verdict(review: str) -> str:
    match = _VERDICT.search(review)
    # Unparseable reviews count as REVISE; the revision limit stops endless loops.
    return match.group(1).upper() if match else "REVISE"


def pick_route(reply: str, routes: list[str]) -> str:
    """First route named in the reply (whole word, case-insensitive); the first route otherwise."""
    for route in sorted(routes, key=len, reverse=True):
        if re.search(rf"\b{re.escape(route)}\b", reply, re.IGNORECASE):
            return route
    return routes[0]


class WorkflowCompiler:
    def __init__(
        self,
        llm_factory: LlmFactory,
        gate: AgentGateClient | None = None,
        tool_transport: httpx.BaseTransport | None = None,
        mcp_servers: "dict[str, McpServerConfig] | McpServerRegistry | None" = None,
        tool_calling: str = "NATIVE",
    ):
        self._tool_calling = tool_calling
        self._llm_factory = llm_factory
        self._gate = gate
        self._tool_transport = tool_transport
        self._registry = (
            mcp_servers
            if isinstance(mcp_servers, McpServerRegistry)
            else McpServerRegistry(mcp_servers or {})
        )

    def compile(
        self, workflow: Workflow, checkpointer: BaseCheckpointSaver | None = None
    ) -> CompiledStateGraph:
        nodes = {n.id: n for n in workflow.nodes}
        outgoing = defaultdict(list)
        for edge in workflow.edges:
            outgoing[edge.source].append(edge)

        def target(node_id: str) -> str:
            return END if nodes[node_id].type == "END" else node_id

        def targets(node_id: str) -> list[str]:
            return [target(e.target) for e in outgoing[node_id]]

        def by_label(node_id: str) -> dict[str, str]:
            return {e.label: target(e.target) for e in outgoing[node_id]}

        graph = StateGraph(_state_schema(workflow))
        for node in workflow.nodes:
            match node:
                case AgentNode() if node.config.agent_id:
                    self._add_agent(graph, workflow, node, targets(node.id))
                case LlmNode() | AgentNode():
                    graph.add_node(node.id, self._llm_node(node.id, node.config))
                    self._add_edges(graph, node.id, targets(node.id))
                case RouterNode():
                    graph.add_node(node.id, self._router_node(node))
                    routes = by_label(node.id)
                    graph.add_conditional_edges(node.id, lambda s, n=node.id: s[n], routes)
                case ReviewerNode():
                    graph.add_node(node.id, self._reviewer_node(node))
                    graph.add_conditional_edges(
                        node.id, self._reviewer_route(node), by_label(node.id) | {END: END}
                    )
                case ConditionNode():
                    graph.add_node(node.id, lambda s: {})
                    graph.add_conditional_edges(
                        node.id, self._condition_route(node), by_label(node.id)
                    )
                case HttpToolNode():
                    add_tool(
                        graph,
                        node.id,
                        self._http_tool(node),
                        lambda s, keys=node.config.payload_keys: {k: s.get(k) for k in keys},
                        next_node=targets(node.id),
                        output_key=node.id,
                        on_denied=node.config.on_denied,
                    )
                case McpToolNode():
                    add_tool(
                        graph,
                        node.id,
                        self._mcp_tool(node),
                        lambda s, args=node.config.arguments: {
                            name: render(template, s) for name, template in args.items()
                        },
                        next_node=targets(node.id),
                        output_key=node.id,
                        on_denied=node.config.on_denied,
                    )
                case ApprovalNode():
                    if self._gate is None:
                        raise CompileError(f"'{node.id}': APPROVAL needs an AgentGate client")
                    message = node.config.message
                    add_approval(
                        graph,
                        node.id,
                        self._gate,
                        node.config.action,
                        node.config.labels,
                        lambda s, m=message: render(m, s) if m else None,
                        next_nodes=targets(node.id),
                    )

        start = next(n for n in workflow.nodes if n.type == "START")
        graph.add_edge(START, target(outgoing[start.id][0].target))
        return graph.compile(checkpointer=checkpointer)

    @staticmethod
    def _add_edges(graph: StateGraph, source: str, destinations: list[str]) -> None:
        for destination in destinations:
            graph.add_edge(source, destination)

    def _ask(self, config: LlmConfig, state: dict, extra_system: str | None = None) -> str:
        llm = self._llm_factory(config.model, config.temperature)
        system = "\n\n".join(p for p in (config.system, extra_system) if p)
        messages = [SystemMessage(render(system, state))] if system else []
        messages.append(HumanMessage(render(config.prompt, state)))
        return str(llm.invoke(messages).content).strip()

    def _llm_node(self, node_id: str, config: LlmConfig):
        def run(state: dict) -> dict:
            return {node_id: self._ask(config, state)}

        return run

    def _router_node(self, node: RouterNode):
        routes = node.config.routes
        instruction = f"Reply with exactly one of: {', '.join(routes)}."

        def run(state: dict) -> dict:
            return {node.id: pick_route(self._ask(node.config, state, instruction), routes)}

        return run

    def _reviewer_node(self, node: ReviewerNode):
        system = node.config.system or ""
        instruction = None if "VERDICT" in system else VERDICT_INSTRUCTION

        def run(state: dict) -> dict:
            done = state.get("revisions", {}).get(node.id, 0)
            return {
                node.id: self._ask(node.config, state, instruction),
                "revisions": {node.id: done + 1},
            }

        return run

    @staticmethod
    def _reviewer_route(node: ReviewerNode):
        def route(state: dict) -> str:
            if parse_verdict(state[node.id]) == "APPROVE":
                return "APPROVE"
            # The first review is not a revision; stop once the budget is spent.
            if state["revisions"][node.id] <= node.config.max_revisions:
                return "REVISE"
            return END

        return route

    @staticmethod
    def _condition_route(node: ConditionNode):
        cases = node.config.cases

        def route(state: dict) -> str:
            value = str(state.get(node.config.key, "")).strip()
            return next((label for label, v in cases.items() if v == value), node.config.default)

        return route

    def _add_agent(
        self, graph: StateGraph, workflow: Workflow, node: AgentNode, next_nodes: list[str]
    ) -> None:
        c = node.config
        spec = workflow.agents.get(c.agent_id)
        if spec is None:
            raise CompileError(f"'{node.id}': agent '{c.agent_id}' was not resolved for this run")
        if c.agent_version is not None and spec.version != c.agent_version:
            raise CompileError(
                f"'{node.id}': needs agent '{c.agent_id}' v{c.agent_version}, got v{spec.version}"
            )
        if self._gate is None:
            raise CompileError(f"'{node.id}': AGENT with tools needs an AgentGate client")
        usable = [t for t in spec.tools if t.permission != "BLOCKED"]
        tools = []
        for function_name, tool in zip(function_names(usable), usable, strict=True):
            if self._registry.get(tool.server) is None:
                raise CompileError(f"'{node.id}': unknown MCP server '{tool.server}'")
            if tool.input_schema is None:
                raise CompileError(f"'{node.id}': no schema for tool {tool.server}/{tool.tool}")
            governed = McpTool(
                f"{node.id}:{tool.server}/{tool.tool}",
                tool.server,
                lambda name=tool.server: self._registry.get(name),
                tool.tool,
                tool.action,
                [],
                self._gate,
            )
            tools.append(AgentTool(function_name, tool, governed))
        add_agent(
            graph,
            node.id,
            spec,
            lambda s, prompt=c.prompt: render(prompt, s),
            self._llm_factory,
            tools,
            next_nodes,
            spec.tool_calling or self._tool_calling,
        )

    def _http_tool(self, node: HttpToolNode) -> HttpTool:
        if self._gate is None:
            raise CompileError(f"'{node.id}': HTTP_TOOL needs an AgentGate client")
        c = node.config
        spec = HttpToolSpec(
            name=node.id, action=c.action, url=c.url, method=c.method, labels=c.labels
        )
        return HttpTool(spec, self._gate, self._tool_transport)

    def _mcp_tool(self, node: McpToolNode) -> McpTool:
        if self._gate is None:
            raise CompileError(f"'{node.id}': MCP_TOOL needs an AgentGate client")
        c = node.config
        if self._registry.get(c.server) is None:
            known = ", ".join(sorted(self._registry.servers())) or "none configured"
            raise CompileError(f"'{node.id}': unknown MCP server '{c.server}' ({known})")
        return McpTool(
            node.id,
            c.server,
            lambda name=c.server: self._registry.get(name),
            c.tool,
            c.action or f"MCP:{c.server}:{c.tool}",
            c.labels,
            self._gate,
        )
