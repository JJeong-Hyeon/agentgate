// Mirrors runtime/app/dsl/schema.py (Workflow DSL) and AgentGate's REST responses.

export type NodeType =
  | "START"
  | "END"
  | "LLM"
  | "AGENT"
  | "ROUTER"
  | "REVIEWER"
  | "CONDITION"
  | "HTTP_TOOL"
  | "MCP_TOOL"
  | "APPROVAL";

export interface DslNode {
  id: string;
  type: NodeType;
  label?: string | null;
  position?: { x: number; y: number } | null;
  config?: Record<string, unknown>;
}

export interface DslEdge {
  source: string;
  target: string;
  label?: string | null;
}

export interface WorkflowDsl {
  schemaVersion?: 1;
  workflowId?: string;
  version?: number;
  name?: string | null;
  nodes: DslNode[];
  edges: DslEdge[];
}

export interface WorkflowSummary {
  id: number;
  workflowId: string;
  name: string;
  latestVersion: number;
  createdAt: string;
  updatedAt: string;
  dsl?: WorkflowDsl;
}

export type ExecutionStatus = "RUNNING" | "WAITING_APPROVAL" | "COMPLETED" | "FAILED";

export interface NodeExecution {
  taskId: string;
  nodeId: string;
  step: string;
  status: "RUNNING" | "WAITING" | "COMPLETED" | "FAILED";
  output?: string | null;
  error?: string | null;
  approvalId?: number | null;
  startedAt?: string | null;
  finishedAt?: string | null;
}

export interface Execution {
  executionId: string;
  workflowId: string;
  workflowVersion: number;
  task: string;
  status: ExecutionStatus;
  waitingApprovalId?: number;
  error?: string;
  createdAt: string;
  updatedAt: string;
  finishedAt?: string;
  nodes?: NodeExecution[];
}

export interface Approval {
  id: number;
  agentId: string;
  action: string;
  target: string | null;
  labels: string[];
  riskLevel: "LOW" | "MEDIUM" | "HIGH" | "BLOCKED";
  status: "PENDING" | "APPROVED" | "REJECTED";
  createdAt: string;
  decidedAt: string | null;
  decidedBy: string | null;
  executionId: string | null;
  reason?: string | null;
}

export interface ApiErrorBody {
  status: number;
  code: string;
  message: string;
  errors?: { path: string; message: string; nodeId?: string | null }[];
}
