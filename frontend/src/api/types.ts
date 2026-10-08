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
  // agents that delegated this work, outermost first, e.g. "lead>research"
  delegatedBy?: string | null;
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
}

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

export interface ApiErrorBody {
  status: number;
  code: string;
  message: string;
  errors?: { path: string; message: string; nodeId?: string | null }[];
}
