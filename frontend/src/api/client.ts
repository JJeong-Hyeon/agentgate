import { SseParser, type SseMessage } from "./sse";
import type { ApiErrorBody, Approval, Execution, WorkflowDsl, WorkflowSummary } from "./types";

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
      headers: { Accept: "text/event-stream", Authorization: this.authorization() },
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
        ...(init.body ? { "Content-Type": "application/json" } : {}),
        ...init.headers,
      },
    });
    if (!response.ok) {
      const body = (await response.json().catch(() => null)) as ApiErrorBody | null;
      throw new ApiError(response.status, body);
    }
    return (await response.json()) as T;
  }
}
