import { SseParser, type SseMessage } from "./sse";
import type {
  Agent,
  AgentDefinition,
  AgentDefinitionVersion,
  ApiErrorBody,
  Approval,
  CreatedAgent,
  Execution,
  IssuedApiKey,
  McpServer,
  McpServerInput,
  McpServerTools,
  RiskLevel,
  ToolRiskServer,
  ToolRiskTool,
  WorkflowDsl,
  WorkflowSummary,
} from "./types";

export interface Credentials {
  username: string;
  password: string;
}

export class ApiError extends Error {
  readonly status: number;
  readonly body: ApiErrorBody | null;

  constructor(status: number, body: ApiErrorBody | null) {
    super(body?.message ?? `Request failed with status ${status}`);
    this.status = status;
    this.body = body;
  }
}

/** Thin wrapper over AgentGate's REST API using HTTP Basic admin credentials. */
export class AgentGateClient {
  private readonly credentials: Credentials;
  private readonly fetchImpl: typeof fetch;

  constructor(credentials: Credentials, fetchImpl: typeof fetch = fetch.bind(globalThis)) {
    this.credentials = credentials;
    this.fetchImpl = fetchImpl;
  }

  listWorkflows(): Promise<WorkflowSummary[]> {
    return this.request("/api/v1/workflows");
  }

  getWorkflow(workflowId: string): Promise<WorkflowSummary> {
    return this.request(`/api/v1/workflows/${encodeURIComponent(workflowId)}`);
  }

  createWorkflow(workflowId: string, dsl: WorkflowDsl): Promise<WorkflowSummary> {
    return this.request("/api/v1/workflows", {
      method: "POST",
      body: JSON.stringify({ workflowId, dsl }),
    });
  }

  addWorkflowVersion(workflowId: string, dsl: WorkflowDsl): Promise<{ version: number; dsl: WorkflowDsl }> {
    return this.request(`/api/v1/workflows/${encodeURIComponent(workflowId)}/versions`, {
      method: "POST",
      body: JSON.stringify({ dsl }),
    });
  }

  getWorkflowVersion(workflowId: string, version: number): Promise<{ version: number; dsl: WorkflowDsl }> {
    return this.request(`/api/v1/workflows/${encodeURIComponent(workflowId)}/versions/${version}`);
  }

  startExecution(workflowId: string, task: string, version?: number): Promise<Execution> {
    return this.request("/api/v1/executions", {
      method: "POST",
      body: JSON.stringify({ workflowId, task, ...(version ? { version } : {}) }),
    });
  }

  getExecution(executionId: string): Promise<Execution> {
    return this.request(`/api/v1/executions/${encodeURIComponent(executionId)}`);
  }

  getApproval(id: number): Promise<Approval> {
    return this.request(`/api/v1/approvals/${id}`);
  }

  /**
   * Subscribes to an execution's live updates. EventSource cannot send the Authorization header, so
   * the stream is read with fetch. Resolves when the server closes the stream or `signal` aborts.
   */
  async streamExecution(
    executionId: string,
    onMessage: (message: SseMessage) => void,
    signal: AbortSignal,
  ): Promise<void> {
    const response = await this.fetchImpl(`/api/v1/executions/${encodeURIComponent(executionId)}/stream`, {
      headers: {
        Accept: "text/event-stream",
        Authorization: this.authorization(),
        "X-Requested-With": "XMLHttpRequest",
      },
      signal,
    });
    if (!response.ok || !response.body) {
      throw new ApiError(response.status, (await response.json().catch(() => null)) as ApiErrorBody | null);
    }
    const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
    const parser = new SseParser();
    for (;;) {
      const { value, done } = await reader.read();
      if (done) return;
      parser.push(value).forEach(onMessage);
    }
  }

  listExecutions(workflowId?: string): Promise<Execution[]> {
    const query = workflowId ? `?workflowId=${encodeURIComponent(workflowId)}` : "";
    return this.request(`/api/v1/executions${query}`);
  }

  listApprovals(status?: Approval["status"]): Promise<Approval[]> {
    return this.request(`/api/v1/approvals${status ? `?status=${status}` : ""}`);
  }

  decideApproval(id: number, decision: "approve" | "reject", decidedBy: string): Promise<Approval> {
    return this.request(`/api/v1/approvals/${id}/${decision}`, {
      method: "POST",
      body: JSON.stringify({ decidedBy }),
    });
  }

  listAgents(): Promise<Agent[]> {
    return this.request("/api/v1/agents");
  }

  getAgent(id: number): Promise<Agent> {
    return this.request(`/api/v1/agents/${id}`);
  }

  createAgent(agentId: string, name: string): Promise<CreatedAgent> {
    return this.request("/api/v1/agents", { method: "POST", body: JSON.stringify({ agentId, name }) });
  }

  /** Issues a new API key; the old one stops working at once. */
  reissueApiKey(id: number): Promise<IssuedApiKey> {
    return this.request(`/api/v1/agents/${id}/api-key`, { method: "POST" });
  }

  /** The latest definition, or null when none has been saved yet. */
  async getAgentDefinition(id: number): Promise<AgentDefinitionVersion | null> {
    try {
      return await this.request(`/api/v1/agents/${id}/definition`);
    } catch (e) {
      if (e instanceof ApiError && e.status === 404 && e.body?.code === "AGENT_DEFINITION_NOT_FOUND") return null;
      throw e;
    }
  }

  getAgentDefinitionVersion(id: number, version: number): Promise<AgentDefinitionVersion> {
    return this.request(`/api/v1/agents/${id}/definition/versions/${version}`);
  }

  listAgentDefinitionVersions(id: number): Promise<AgentDefinitionVersion[]> {
    return this.request(`/api/v1/agents/${id}/definition/versions`);
  }

  saveAgentDefinition(id: number, definition: AgentDefinition): Promise<AgentDefinitionVersion> {
    return this.request(`/api/v1/agents/${id}/definition`, { method: "PUT", body: JSON.stringify(definition) });
  }

  listMcpServers(): Promise<McpServer[]> {
    return this.request("/api/v1/mcp-servers");
  }

  createMcpServer(input: McpServerInput): Promise<McpServer> {
    return this.request("/api/v1/mcp-servers", { method: "POST", body: JSON.stringify(input) });
  }

  updateMcpServer(id: number, input: McpServerInput): Promise<McpServer> {
    return this.request(`/api/v1/mcp-servers/${id}`, { method: "PUT", body: JSON.stringify(input) });
  }

  deleteMcpServer(id: number): Promise<void> {
    return this.request(`/api/v1/mcp-servers/${id}`, { method: "DELETE" });
  }

  listToolRisks(refresh = false): Promise<ToolRiskServer[]> {
    return this.request(`/api/v1/tool-risks${refresh ? "?refresh=true" : ""}`);
  }

  setToolRisk(server: string, tool: string, riskLevel: RiskLevel): Promise<ToolRiskTool> {
    return this.request("/api/v1/tool-risks", { method: "PUT", body: JSON.stringify({ server, tool, riskLevel }) });
  }

  clearToolRisk(server: string, tool: string): Promise<void> {
    const query = `server=${encodeURIComponent(server)}&tool=${encodeURIComponent(tool)}`;
    return this.request(`/api/v1/tool-risks?${query}`, { method: "DELETE" });
  }

  applyToolRiskSuggestions(): Promise<{ applied: number }> {
    return this.request("/api/v1/tool-risks/apply-suggestions", { method: "POST" });
  }

  /** Tools of the MCP servers the runtime is configured with. */
  listTools(refresh = false): Promise<McpServerTools[]> {
    return this.request(`/api/v1/tools${refresh ? "?refresh=true" : ""}`);
  }

  private authorization(): string {
    const { username, password } = this.credentials;
    return `Basic ${btoa(`${username}:${password}`)}`;
  }

  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const response = await this.fetchImpl(path, {
      ...init,
      headers: {
        Accept: "application/json",
        Authorization: this.authorization(),
        // Makes AgentGate answer 401 without WWW-Authenticate, so the browser's own login dialog never appears.
        "X-Requested-With": "XMLHttpRequest",
        ...(init.body ? { "Content-Type": "application/json" } : {}),
        ...init.headers,
      },
    });
    if (!response.ok) {
      const body = (await response.json().catch(() => null)) as ApiErrorBody | null;
      throw new ApiError(response.status, body);
    }
    // 204 No Content (e.g. DELETE) has no body to parse.
    if (response.status === 204) return undefined as T;
    return (await response.json()) as T;
  }
}
