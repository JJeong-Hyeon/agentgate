import { SseParser, type SseMessage } from "./sse";
import type {
  Agent,
  AgentDefinition,
  AgentDefinitionVersion,
  AgentMemory,
  ApiErrorBody,
  Approval,
  AuditLog,
  CreatedAgent,
  Dashboard,
  DashboardPeriod,
  Execution,
  IssuedApiKey,
  McpServer,
  MemorySaveResult,
  McpServerInput,
  McpServerTools,
  Me,
  RiskLevel,
  ToolRiskServer,
  ToolRiskTool,
  UserInput,
  UserSummary,
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

/** Fired (with the client as `detail`) when the server says the session is gone (expired, signed out elsewhere). */
export const UNAUTHORIZED_EVENT = "agentgate:unauthorized";

function csrfToken(): string | null {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : null;
}

/**
 * Thin wrapper over AgentGate's REST API. The console signs in with a session cookie (no credentials here);
 * with `credentials` it sends HTTP Basic instead, as scripts do.
 */
export class AgentGateClient {
  private readonly credentials: Credentials | null;
  private readonly fetchImpl: typeof fetch;

  constructor(credentials: Credentials | null = null, fetchImpl: typeof fetch = fetch.bind(globalThis)) {
    this.credentials = credentials;
    this.fetchImpl = fetchImpl;
  }

  /** Signs in: the server answers with the user and sets the session cookie. */
  login(username: string, password: string): Promise<Me> {
    return this.request("/api/v1/auth/login", { method: "POST", body: JSON.stringify({ username, password }) });
  }

  logout(): Promise<{ logoutUrl?: string } | undefined> {
    return this.request("/api/v1/auth/logout", { method: "POST" });
  }

  /** Headers that authenticate a request: Basic, or the CSRF token that goes with the session cookie. */
  private authHeaders(method = "GET"): Record<string, string> {
    if (this.credentials) return { Authorization: this.authorization() };
    const token = ["GET", "HEAD", "OPTIONS"].includes(method) ? null : csrfToken();
    return token ? { "X-XSRF-TOKEN": token } : {};
  }

  getDashboard(period: DashboardPeriod): Promise<Dashboard> {
    return this.request(`/api/v1/dashboard?period=${period}`);
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
        ...this.authHeaders(),
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

  listApprovals(status?: Approval["status"], executionId?: string): Promise<Approval[]> {
    const params = new URLSearchParams();
    if (status) params.set("status", status);
    if (executionId) params.set("executionId", executionId);
    const query = params.toString();
    return this.request(`/api/v1/approvals${query ? `?${query}` : ""}`);
  }

  /** Governance decisions made during an execution, newest first. */
  listExecutionAuditLogs(executionId: string): Promise<AuditLog[]> {
    return this.request(`/api/v1/audit-logs?executionId=${encodeURIComponent(executionId)}&limit=1000`);
  }

  /** `decidedBy` is always the signed-in user; the server ignores any body value. */
  decideApproval(id: number, decision: "approve" | "reject"): Promise<Approval> {
    return this.request(`/api/v1/approvals/${id}/${decision}`, { method: "POST" });
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

  /** The agent's newest memories, or the best matches for `query`. */
  listAgentMemories(agentId: string, query?: string): Promise<AgentMemory[]> {
    const search = query?.trim() ? `?query=${encodeURIComponent(query.trim())}` : "";
    return this.request(`/api/v1/agents/${encodeURIComponent(agentId)}/memories${search}`);
  }

  addAgentMemory(agentId: string, content: string): Promise<MemorySaveResult> {
    return this.request(`/api/v1/agents/${encodeURIComponent(agentId)}/memories`, {
      method: "POST",
      body: JSON.stringify({ content }),
    });
  }

  deleteAgentMemory(agentId: string, id: number): Promise<void> {
    return this.request(`/api/v1/agents/${encodeURIComponent(agentId)}/memories/${id}`, { method: "DELETE" });
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

  getMe(): Promise<Me> {
    return this.request("/api/v1/me");
  }

  changeMyPassword(currentPassword: string, newPassword: string): Promise<void> {
    return this.request("/api/v1/me/password", {
      method: "PUT",
      body: JSON.stringify({ currentPassword, newPassword }),
    });
  }

  listUsers(): Promise<UserSummary[]> {
    return this.request("/api/v1/users");
  }

  createUser(input: UserInput): Promise<UserSummary> {
    return this.request("/api/v1/users", { method: "POST", body: JSON.stringify(input) });
  }

  updateUser(id: number, input: UserInput): Promise<UserSummary> {
    return this.request(`/api/v1/users/${id}`, { method: "PUT", body: JSON.stringify(input) });
  }

  deleteUser(id: number): Promise<void> {
    return this.request(`/api/v1/users/${id}`, { method: "DELETE" });
  }

  private authorization(): string {
    const { username, password } = this.credentials!;
    return `Basic ${btoa(`${username}:${password}`)}`;
  }

  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const response = await this.fetchImpl(path, {
      ...init,
      headers: {
        Accept: "application/json",
        ...this.authHeaders(init.method),
        // Makes AgentGate answer 401 without WWW-Authenticate, so the browser's own login dialog never appears.
        "X-Requested-With": "XMLHttpRequest",
        ...(init.body ? { "Content-Type": "application/json" } : {}),
        ...init.headers,
      },
    });
    if (!response.ok) {
      const body = (await response.json().catch(() => null)) as ApiErrorBody | null;
      // A session that ended while the console was open: back to the sign-in page (not for sign-in itself).
      if (response.status === 401 && !this.credentials && path !== "/api/v1/auth/login") {
        window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT, { detail: this }));
      }
      throw new ApiError(response.status, body);
    }
    // 204 No Content (e.g. DELETE) has no body to parse.
    if (response.status === 204) return undefined as T;
    return (await response.json()) as T;
  }
}
