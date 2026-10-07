import type { Edge, Node } from "@xyflow/react";
import type { DslNode, WorkflowDsl } from "../api/types";

export interface FlowNodeData extends Record<string, unknown> {
  dsl: DslNode;
  error?: string;
  status?: string;
}

export type FlowNode = Node<FlowNodeData, "dsl">;

const COLUMN_WIDTH = 240;
const ROW_HEIGHT = 110;

/**
 * Layered layout for DSL documents without builder positions: each node goes in the column of its
 * breadth-first distance from START, so loop-back edges (e.g. REVISE) do not push nodes right.
 */
export function autoLayout(dsl: WorkflowDsl): Map<string, { x: number; y: number }> {
  const next = new Map<string, string[]>();
  for (const edge of dsl.edges) {
    next.set(edge.source, [...(next.get(edge.source) ?? []), edge.target]);
  }
  const depth = new Map<string, number>();
  const start = dsl.nodes.find((n) => n.type === "START");
  const queue = start ? [start.id] : [];
  if (start) depth.set(start.id, 0);
  while (queue.length > 0) {
    const id = queue.shift()!;
    for (const target of next.get(id) ?? []) {
      if (!depth.has(target)) {
        depth.set(target, depth.get(id)! + 1);
        queue.push(target);
      }
    }
  }

  const rows = new Map<number, number>();
  const positions = new Map<string, { x: number; y: number }>();
  for (const node of dsl.nodes) {
    // Unreachable nodes are still shown, in the first column.
    const column = depth.get(node.id) ?? 0;
    const row = rows.get(column) ?? 0;
    rows.set(column, row + 1);
    positions.set(node.id, { x: column * COLUMN_WIDTH, y: row * ROW_HEIGHT });
  }
  return positions;
}

export function edgeId(edge: { source: string; target: string; label?: string | null }): string {
  return `${edge.source}->${edge.target}${edge.label ? `:${edge.label}` : ""}`;
}

export function dslToFlow(dsl: WorkflowDsl): { nodes: FlowNode[]; edges: Edge[] } {
  const layout = autoLayout(dsl);
  const nodes = dsl.nodes.map<FlowNode>((node) => ({
    id: node.id,
    type: "dsl",
    position: node.position ?? layout.get(node.id)!,
    data: { dsl: node },
  }));
  const edges = dsl.edges.map<Edge>((edge) => ({
    id: edgeId(edge),
    source: edge.source,
    target: edge.target,
    label: edge.label ?? undefined,
    type: "smoothstep",
  }));
  return { nodes, edges: routeEdges(nodes, edges) };
}

/**
 * Edges that point back to a node at or left of their source (e.g. REVISE) leave and enter through the
 * hidden bottom handles, so they loop under the row instead of overlapping the forward edges.
 */
export function routeEdges(nodes: FlowNode[], edges: Edge[]): Edge[] {
  const x = new Map(nodes.map((n) => [n.id, n.position.x]));
  return edges.map((edge) => {
    const backwards = (x.get(edge.target) ?? 0) <= (x.get(edge.source) ?? 0);
    return backwards
      ? { ...edge, sourceHandle: "back-out", targetHandle: "back-in", className: "edge-back" }
      : { ...edge, sourceHandle: null, targetHandle: null, className: undefined };
  });
}

/** Inverse of dslToFlow; the canvas position becomes the node's DSL position. */
export function flowToDsl(
  nodes: FlowNode[],
  edges: Edge[],
  meta: Pick<WorkflowDsl, "name"> = {},
): WorkflowDsl {
  return {
    ...meta,
    nodes: nodes.map((node) => ({
      ...node.data.dsl,
      position: { x: Math.round(node.position.x), y: Math.round(node.position.y) },
    })),
    edges: edges.map((edge) => ({
      source: edge.source,
      target: edge.target,
      ...(typeof edge.label === "string" && edge.label ? { label: edge.label } : {}),
    })),
  };
}
