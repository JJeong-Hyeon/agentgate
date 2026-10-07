import type { Edge, Node } from "@xyflow/react";
import type { DslNode, WorkflowDsl } from "../api/types";

export interface FlowNodeData extends Record<string, unknown> {
  label: string;
  dsl: DslNode;
}

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

export function dslToFlow(dsl: WorkflowDsl): { nodes: Node<FlowNodeData>[]; edges: Edge[] } {
  const layout = autoLayout(dsl);
  const nodes = dsl.nodes.map<Node<FlowNodeData>>((node) => ({
    id: node.id,
    type: node.type === "START" ? "input" : node.type === "END" ? "output" : "default",
    position: node.position ?? layout.get(node.id)!,
    className: `node node-${node.type.toLowerCase()}`,
    data: { label: node.label ?? node.id, dsl: node },
  }));
  const edges = dsl.edges.map<Edge>((edge) => ({
    id: `${edge.source}->${edge.target}${edge.label ? `:${edge.label}` : ""}`,
    source: edge.source,
    target: edge.target,
    label: edge.label ?? undefined,
  }));
  return { nodes, edges };
}
