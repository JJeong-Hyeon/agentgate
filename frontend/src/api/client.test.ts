import { AgentGateClient, ApiError } from "./client";

function fakeFetch(status: number, body: unknown) {
  return vi.fn(async (..._args: Parameters<typeof fetch>) => new Response(JSON.stringify(body), { status }));
}

describe("AgentGateClient", () => {
  it("sends basic auth and parses JSON", async () => {
    const fetchImpl = fakeFetch(200, [{ workflowId: "research" }]);

    const workflows = await new AgentGateClient({ username: "admin", password: "pw" }, fetchImpl).listWorkflows();

    expect(workflows).toEqual([{ workflowId: "research" }]);
    const [url, init] = fetchImpl.mock.calls[0];
    expect(url).toBe("/api/v1/workflows");
    expect((init!.headers as Record<string, string>).Authorization).toBe(`Basic ${btoa("admin:pw")}`);
  });

  it("posts approval decisions as JSON", async () => {
    const fetchImpl = fakeFetch(200, { id: 3, status: "APPROVED" });

    await new AgentGateClient({ username: "a", password: "b" }, fetchImpl).decideApproval(3, "approve", "alice");

    const [url, init] = fetchImpl.mock.calls[0];
    expect(url).toBe("/api/v1/approvals/3/approve");
    expect(init!.method).toBe("POST");
    expect(init!.body).toBe(JSON.stringify({ decidedBy: "alice" }));
    expect((init!.headers as Record<string, string>)["Content-Type"]).toBe("application/json");
  });

  it("raises ApiError with AgentGate's error body", async () => {
    const client = new AgentGateClient(
      { username: "a", password: "b" },
      fakeFetch(422, { status: 422, code: "INVALID_WORKFLOW", message: "bad", errors: [] }),
    );

    const error = await client.listWorkflows().catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(422);
    expect((error as ApiError).body?.code).toBe("INVALID_WORKFLOW");
  });
});
