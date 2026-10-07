import type { ApiErrorBody, Approval, Execution, WorkflowSummary } from "./types";

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

  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const { username, password } = this.credentials;
    const response = await this.fetchImpl(path, {
      ...init,
      headers: {
        Accept: "application/json",
        Authorization: `Basic ${btoa(`${username}:${password}`)}`,
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
