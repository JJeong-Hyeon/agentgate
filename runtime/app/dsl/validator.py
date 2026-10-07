"""Structural validation of a Workflow DSL document.

Collects every problem instead of stopping at the first, so the builder can mark them all.
"""

from collections import defaultdict, deque
from string import Formatter
from typing import Any

from pydantic import BaseModel, ValidationError

from app.dsl.schema import (
    OUTPUT_TYPES,
    RESERVED_IDS,
    ConditionNode,
    HttpToolNode,
    McpToolNode,
    ReviewerNode,
    RouterNode,
    Workflow,
)

REVIEW_LABELS = {"APPROVE", "REVISE"}


class ValidationIssue(BaseModel):
    path: str
    message: str
    node_id: str | None = None


def validate_workflow(data: dict[str, Any]) -> tuple[Workflow | None, list[ValidationIssue]]:
    try:
        workflow = Workflow.model_validate(data)
    except ValidationError as e:
        return None, [
            ValidationIssue(path=".".join(str(p) for p in err["loc"]), message=err["msg"])
            for err in e.errors()
        ]
    issues = _Checker(workflow).run()
    return (workflow if not issues else None), issues


def expected_labels(node) -> set[str] | None:
    """Labels a branching node's outgoing edges must carry; None for non-branching nodes."""
    if isinstance(node, RouterNode):
        return set(node.config.routes)
    if isinstance(node, ReviewerNode):
        return REVIEW_LABELS
    if isinstance(node, ConditionNode):
        return set(node.config.cases) | {node.config.default}
    return None


class _Checker:
    def __init__(self, workflow: Workflow):
        self.workflow = workflow
        self.issues: list[ValidationIssue] = []
        self.nodes = {}
        self.outgoing = defaultdict(list)
        self.incoming = defaultdict(list)

    def error(self, path: str, message: str, node_id: str | None = None) -> None:
        self.issues.append(ValidationIssue(path=path, message=message, node_id=node_id))

    def run(self) -> list[ValidationIssue]:
        self.check_nodes()
        self.check_edges()
        if self.issues:
            # Graph checks below assume ids and edges are sound.
            return self.issues
        self.check_start_and_end()
        self.check_branches()
        self.check_reachability()
        self.check_cycles()
        self.check_references()
        return self.issues

    def check_nodes(self) -> None:
        for i, node in enumerate(self.workflow.nodes):
            if node.id in self.nodes:
                self.error(f"nodes.{i}.id", f"Duplicate node id '{node.id}'", node.id)
            elif node.id in RESERVED_IDS:
                self.error(f"nodes.{i}.id", f"'{node.id}' is reserved", node.id)
            else:
                self.nodes[node.id] = node

    def check_edges(self) -> None:
        seen = set()
        for i, edge in enumerate(self.workflow.edges):
            for end in ("source", "target"):
                if getattr(edge, end) not in self.nodes:
                    self.error(f"edges.{i}.{end}", f"Unknown node '{getattr(edge, end)}'")
            key = (edge.source, edge.target, edge.label)
            if key in seen:
                self.error(f"edges.{i}", "Duplicate edge")
            seen.add(key)
            self.outgoing[edge.source].append(edge)
            self.incoming[edge.target].append(edge)

    def check_start_and_end(self) -> None:
        starts = [n for n in self.nodes.values() if n.type == "START"]
        ends = [n for n in self.nodes.values() if n.type == "END"]
        if len(starts) != 1:
            self.error("nodes", f"Exactly one START node is required, found {len(starts)}")
        if not ends:
            self.error("nodes", "At least one END node is required")
        for node in starts:
            if self.incoming[node.id]:
                self.error("edges", "START cannot have incoming edges", node.id)
            if len(self.outgoing[node.id]) != 1:
                self.error("edges", "START must have exactly one outgoing edge", node.id)
        for node in ends:
            if self.outgoing[node.id]:
                self.error("edges", "END cannot have outgoing edges", node.id)

    def check_branches(self) -> None:
        for node in self.nodes.values():
            edges = self.outgoing[node.id]
            labels = [e.label for e in edges]
            expected = expected_labels(node)
            if expected is None:
                if any(labels):
                    self.error("edges", f"Edges from {node.type} must not have labels", node.id)
                continue
            if None in labels:
                self.error("edges", f"Every edge from {node.type} needs a label", node.id)
            given = [label for label in labels if label]
            if len(given) != len(set(given)):
                self.error("edges", "Each label may be used by only one edge", node.id)
            if set(given) != expected:
                self.error(
                    "edges",
                    f"Outgoing edge labels must be {sorted(expected)}, got {sorted(set(given))}",
                    node.id,
                )

    def check_reachability(self) -> None:
        start = next((n.id for n in self.nodes.values() if n.type == "START"), None)
        ends = [n.id for n in self.nodes.values() if n.type == "END"]
        if start is None or not ends:
            return
        forward = {n: [e.target for e in self.outgoing[n]] for n in self.nodes}
        backward = {n: [e.source for e in self.incoming[n]] for n in self.nodes}
        reachable = _walk([start], forward)
        finishing = _walk(ends, backward)
        for node_id in self.nodes:
            if node_id not in reachable:
                self.error("nodes", "Not reachable from START", node_id)
            elif node_id not in finishing:
                self.error("nodes", "No path to an END node", node_id)

    def check_cycles(self) -> None:
        # Every cycle must pass through a REVIEWER, whose maxRevisions bounds it.
        graph = {
            n: [e.target for e in self.outgoing[n] if self.nodes[e.target].type != "REVIEWER"]
            for n, node in self.nodes.items()
            if node.type != "REVIEWER"
        }
        cycle_node = _find_cycle(graph)
        if cycle_node:
            self.error("edges", "Cycle without a REVIEWER would loop forever", cycle_node)

    def check_references(self) -> None:
        available = {"task"} | {n.id for n in self.nodes.values() if n.type in OUTPUT_TYPES}
        for node in self.nodes.values():
            config = getattr(node, "config", None)
            for field in ("prompt", "system", "message"):
                if text := getattr(config, field, None):
                    self.check_template(f"config.{field}", text, node.id, available)
            if isinstance(node, McpToolNode):
                for arg, template in node.config.arguments.items():
                    self.check_template(f"config.arguments.{arg}", template, node.id, available)
            if isinstance(node, HttpToolNode):
                for key in node.config.payload_keys:
                    if key not in available:
                        self.error("config.payloadKeys", f"Unknown state key '{key}'", node.id)
            if isinstance(node, ConditionNode) and node.config.key not in available:
                self.error("config.key", f"Unknown state key '{node.config.key}'", node.id)

    def check_template(self, path: str, text: str, node_id: str, available: set[str]) -> None:
        try:
            names = {name for _, name, _, _ in Formatter().parse(text) if name is not None}
        except ValueError as e:
            self.error(path, f"Invalid template: {e}", node_id)
            return
        for name in sorted(names - available):
            self.error(path, f"Unknown variable '{{{name}}}'", node_id)


def _walk(starts: list[str], graph: dict[str, list[str]]) -> set[str]:
    seen, queue = set(starts), deque(starts)
    while queue:
        for nxt in graph[queue.popleft()]:
            if nxt not in seen:
                seen.add(nxt)
                queue.append(nxt)
    return seen


def _find_cycle(graph: dict[str, list[str]]) -> str | None:
    WHITE, GREY, BLACK = 0, 1, 2
    color = dict.fromkeys(graph, WHITE)
    for root in graph:
        if color[root] != WHITE:
            continue
        stack = [(root, iter(graph[root]))]
        color[root] = GREY
        while stack:
            node, children = stack[-1]
            child = next(children, None)
            if child is None:
                color[node] = BLACK
                stack.pop()
            elif color[child] == GREY:
                return child
            elif color[child] == WHITE:
                color[child] = GREY
                stack.append((child, iter(graph[child])))
    return None
