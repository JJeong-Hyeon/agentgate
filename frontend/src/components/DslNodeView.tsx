import { Handle, Position, type NodeProps } from "@xyflow/react";
import type { FlowNode } from "../dsl/flow";

const TYPE_CLASS: Record<string, string> = {
  LLM: "agent",
  AGENT: "agent",
  ROUTER: "agent",
  REVIEWER: "agent",
  HTTP_TOOL: "tool",
  APPROVAL: "tool",
};

const STATUS_TEXT: Record<string, string> = {
  RUNNING: "실행 중",
  WAITING: "승인 대기",
  COMPLETED: "완료",
  FAILED: "실패",
};

/** Canvas node for any DSL node type: type badge, label, and handles where edges are allowed. */
export function DslNodeView({ data, selected }: NodeProps<FlowNode>) {
  const { dsl, error, status } = data;
  const kind = TYPE_CLASS[dsl.type] ?? "flow";
  const classes = ["dsl-node", `dsl-node-${kind}`, selected && "selected", error && "has-error", status && `status-${status.toLowerCase()}`]
    .filter(Boolean)
    .join(" ");
  return (
    <div className={classes} title={error}>
      {dsl.type !== "START" && <Handle type="target" position={Position.Left} />}
      {/* Anchors for loop-back edges only (see routeEdges); not offered for drawing new edges. */}
      <Handle id="back-in" type="target" position={Position.Bottom} className="back-handle" isConnectable={false} />
      <span className="dsl-node-type">{dsl.type}</span>
      <span className="dsl-node-label">{dsl.label || dsl.id}</span>
      {status && <span className="dsl-node-status">{STATUS_TEXT[status] ?? status}</span>}
      {dsl.type !== "END" && <Handle type="source" position={Position.Right} />}
      <Handle id="back-out" type="source" position={Position.Bottom} className="back-handle" isConnectable={false} />
    </div>
  );
}

export const nodeTypes = { dsl: DslNodeView };
