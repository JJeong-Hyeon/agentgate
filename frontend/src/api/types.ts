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

export type ExecutionStatus = "RUNNING" | "WAITING_APPROVAL" | "COMPLETED" | "STOPPED" | "FAILED";

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
  // agentId → definition version the run uses
  agentVersions?: Record<string, number>;
  nodes?: NodeExecution[];
}

// One governance decision AgentGate made (and why), as recorded in the audit log.
export interface AuditLog {
  id: number;
  agentId: string;
  action: string;
  target: string | null;
  labels: string[];
  riskLevel: "LOW" | "MEDIUM" | "HIGH" | "BLOCKED";
  status: "ALLOWED" | "APPROVAL_REQUIRED" | "BLOCKED";
  approvalId: number | null;
  basis: string | null;
  delegatedBy: string | null;
  executionId: string | null;
  createdAt: string;
}

export interface Approval {
  id: number;
  agentId: string;
  action: string;
  target: string | null;
  labels: string[];
  riskLevel: "LOW" | "MEDIUM" | "HIGH" | "BLOCKED";
  status: "PENDING" | "APPROVED" | "REJECTED" | "EXPIRED";
  createdAt: string;
  decidedAt: string | null;
  decidedBy: string | null;
  executionId: string | null;
  reason?: string | null;
  // agents that delegated this work, outermost first, e.g. "lead>research"
  delegatedBy?: string | null;
  // the arguments the call will run with, secrets masked
  payloadPreview?: string | null;
}

export type RiskLevel = "LOW" | "MEDIUM" | "HIGH" | "BLOCKED";

export interface Agent {
  id: number;
  agentId: string;
  name: string;
  description: string | null;
  maxRiskLevel: RiskLevel | null;
  // 0 until a definition is saved
  latestDefinitionVersion: number;
  apiKeyIssuedAt: string | null;
  createdAt: string;
}

export interface IssuedApiKey {
  id: number;
  agentId: string;
  // Shown once; only its hash is stored.
  apiKey: string;
  issuedAt: string;
}

export interface CreatedAgent {
  id: number;
  agentId: string;
  name: string;
  // Shown once; only its hash is stored.
  apiKey: string;
  createdAt: string;
}

export type ToolPermission = "AUTO" | "APPROVAL" | "BLOCKED";
export type ToolCallingMode = "NATIVE" | "JSON";

export interface AgentToolDefinition {
  server: string;
  tool: string;
  permission: ToolPermission;
  labels?: string[];
}

export interface AgentDelegateDefinition {
  agentId: string;
  permission: ToolPermission;
}

export interface AgentMemoryDefinition {
  read: ToolPermission;
  write: ToolPermission;
  // memories looked up with the task and given to the agent at the start of each run
  recall?: number | null;
}

export interface AgentMemory {
  id: number;
  agentId: string;
  content: string;
  // AGENT: saved by the agent during a run; USER: added in the console
  source: "AGENT" | "USER";
  executionId: string | null;
  createdBy: string | null;
  createdAt: string;
  // set in search results
  score: number | null;
  matchedBy: "SEMANTIC" | "KEYWORD" | null;
}

// What adding a memory did: stored it, or found the agent already remembers it word for word.
export interface MemorySaveResult {
  memory: AgentMemory;
  duplicate: boolean;
  replaced: number | null;
  similar: AgentMemory[];
}

export interface AgentDefinition {
  description?: string | null;
  model?: string | null;
  temperature?: number | null;
  systemPrompt: string;
  tools: AgentToolDefinition[];
  maxSteps?: number | null;
  outputSchema?: Record<string, unknown> | null;
  toolCalling?: ToolCallingMode | null;
  // agents this one may hand work to
  delegates?: AgentDelegateDefinition[];
  // long-term memory access; null → none
  memory?: AgentMemoryDefinition | null;
  // how much the model reasons before answering; null → the runtime's default
  reasoningEffort?: ReasoningEffort | null;
}

export type ReasoningEffort = "none" | "minimal" | "low" | "medium" | "high";

export interface AgentDefinitionVersion {
  agentId: string;
  version: number;
  createdAt: string;
  definition?: AgentDefinition;
}

export interface McpToolInfo {
  name: string;
  title: string | null;
  description: string | null;
  inputSchema: Record<string, unknown>;
  annotations: Record<string, unknown> | null;
}

export interface McpServerTools {
  server: string;
  transport: "url" | "stdio";
  // where the server is registered: AgentGate, or the runtime's config file
  source?: "agentgate" | "runtime";
  tools: McpToolInfo[];
  error: string | null;
}

export interface McpServer {
  id: number;
  name: string;
  url: string;
  description: string | null;
  enabled: boolean;
  // values are never returned
  headerNames: string[];
  createdAt: string;
  updatedAt: string;
}

export interface McpServerInput {
  name: string;
  url: string;
  description?: string | null;
  enabled?: boolean;
  // replaces all stored headers when present; omitted keeps them
  headers?: Record<string, string>;
}

export interface ToolRiskTool {
  name: string;
  title: string | null;
  description: string | null;
  action: string;
  riskLevel: RiskLevel | null;
  policyId: number | null;
  effectiveRiskLevel: RiskLevel;
  suggestedRiskLevel: RiskLevel | null;
}

export interface ToolRiskServer {
  server: string;
  source: "agentgate" | "runtime" | null;
  transport: "url" | "stdio";
  error: string | null;
  tools: ToolRiskTool[];
}

export type Role = "ADMIN" | "EDITOR" | "APPROVER" | "VIEWER";

export interface UserSummary {
  id: number;
  username: string;
  displayName: string | null;
  roles: Role[];
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

/** GET /api/v1/me returns the same shape as a user list entry. */
export type Me = UserSummary;

export interface UserInput {
  username: string;
  displayName?: string | null;
  // required on create; omitted on update keeps the current password
  password?: string;
  roles: Role[];
  enabled?: boolean;
}

export interface ApiErrorBody {
  status: number;
  code: string;
  message: string;
  errors?: { path: string; message: string; nodeId?: string | null }[];
}

export type DashboardPeriod = "24h" | "7d";

export interface DashboardBucket {
  start: string;
  total: number;
  completed: number;
  stopped: number;
  failed: number;
}

export interface ModelUsage {
  model: string | null;
  calls: number;
  avgLatencyMs: number | null;
  inputTokens: number | null;
  outputTokens: number | null;
}

// The console's operations overview for one period (GET /api/v1/dashboard).
export interface Dashboard {
  period: DashboardPeriod;
  since: string;
  executions: {
    total: number;
    byStatus: Record<string, number>;
    running: number;
    waitingApproval: number;
    avgDurationMs: number | null;
    buckets: DashboardBucket[];
  };
  approvals: {
    pending: number;
    oldestPending: {
      id: number;
      agentId: string;
      action: string;
      riskLevel: RiskLevel;
      executionId: string | null;
      createdAt: string;
    }[];
    decided: Record<string, number>;
    avgWaitMs: number | null;
  };
  governance: {
    total: number;
    allowed: number;
    approvalRequired: number;
    blocked: number;
    blockRate: number;
    topBlocked: { action: string; count: number }[];
  };
  models: ModelUsage[];
  recentProblems: { executionId: string; workflowId: string; status: string; error: string | null; createdAt: string }[];
}
