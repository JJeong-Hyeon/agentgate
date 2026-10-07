import type { Execution, ExecutionStatus, NodeExecution } from "../api/types";

export interface ExecutionUpdate {
  event: { type: string };
  // Execution fields after the event; `nodes` holds only the node the event touched, if any.
  execution: Execution;
}

export function isFinished(status: ExecutionStatus): boolean {
  return status === "COMPLETED" || status === "FAILED";
}

/** Merges a live update into the current execution, upserting the touched node by task id. */
export function applyUpdate(current: Execution, update: ExecutionUpdate): Execution {
  const nodes = [...(current.nodes ?? [])];
  for (const node of update.execution.nodes ?? []) {
    const index = nodes.findIndex((n) => n.taskId === node.taskId);
    if (index === -1) nodes.push(node);
    else nodes[index] = node;
  }
  if (update.event.type === "EXECUTION_FAILED") {
    // AgentGate marks the steps that were still running as failed.
    for (let i = 0; i < nodes.length; i++) {
      if (nodes[i].status === "RUNNING") nodes[i] = { ...nodes[i], status: "FAILED", error: update.execution.error };
    }
  }
  return { ...update.execution, nodes };
}

/** Status per DSL node: the latest step recorded for it (tool sub-steps share the node id). */
export function nodeStatuses(nodes: NodeExecution[] = []): Map<string, NodeExecution["status"]> {
  const statuses = new Map<string, NodeExecution["status"]>();
  for (const node of nodes) statuses.set(node.nodeId, node.status);
  return statuses;
}

export function duration(node: NodeExecution): string | null {
  if (!node.startedAt || !node.finishedAt) return null;
  const ms = new Date(node.finishedAt).getTime() - new Date(node.startedAt).getTime();
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`;
}
