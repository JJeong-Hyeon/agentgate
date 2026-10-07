import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import type { Execution } from "../api/types";
import { AuthProvider } from "../auth/AuthContext";
import { ExecutionPage } from "./ExecutionPage";

const DSL = {
  nodes: [
    { id: "start", type: "START" },
    { id: "plan", type: "AGENT", config: { prompt: "{task}" } },
    { id: "report", type: "HTTP_TOOL", config: { action: "SEND_REPORT", url: "http://x" } },
    { id: "end", type: "END" },
  ],
  edges: [
    { source: "start", target: "plan" },
    { source: "plan", target: "report" },
    { source: "report", target: "end" },
  ],
};

const running: Execution = {
  executionId: "e1",
  workflowId: "research",
  workflowVersion: 2,
  task: "Compare runtimes",
  status: "RUNNING",
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
  nodes: [
    {
      taskId: "t1",
      nodeId: "plan",
      step: "plan",
      status: "COMPLETED",
      output: '{"plan": "1. step"}',
      startedAt: "2026-01-01T00:00:00Z",
      finishedAt: "2026-01-01T00:00:02Z",
    },
  ],
};

const waiting = {
  event: { type: "EXECUTION_WAITING", approvalId: 7 },
  execution: { ...running, status: "WAITING_APPROVAL", waitingApprovalId: 7, nodes: undefined },
};
const nodeWaiting = {
  event: { type: "NODE_WAITING" },
  execution: {
    ...running,
    nodes: [{ taskId: "t2", nodeId: "report", step: "report.approval", status: "WAITING", approvalId: 7 }],
  },
};

const sse = (event: string, data: unknown) => `event:${event}\ndata:${JSON.stringify(data)}\n\n`;

function streamResponse(chunks: string[]): Response {
  const encoder = new TextEncoder();
  return new Response(
    new ReadableStream({
      start(controller) {
        chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)));
        // Left open, like a live stream.
      },
    }),
    { headers: { "Content-Type": "text/event-stream" } },
  );
}

function renderPage() {
  sessionStorage.setItem("agentgate.credentials", JSON.stringify({ username: "admin", password: "pw" }));
  const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.endsWith("/stream")) {
      return streamResponse([sse("snapshot", running), sse("update", nodeWaiting), sse("update", waiting)]);
    }
    if (url.includes("/versions/2")) return Response.json({ version: 2, dsl: DSL });
    if (url === "/api/v1/approvals/7" && !init?.method) {
      return Response.json({
        id: 7,
        agentId: "runtime-agent",
        action: "SEND_REPORT",
        target: "http://x",
        labels: ["PII"],
        riskLevel: "HIGH",
        status: "PENDING",
        reason: "Send the report about runtimes?",
      });
    }
    if (url === "/api/v1/approvals/7/approve") return Response.json({ id: 7, status: "APPROVED" });
    return new Response("{}", { status: 404 });
  });
  render(
    <AuthProvider fetchImpl={fetchImpl as unknown as typeof fetch}>
      <MemoryRouter initialEntries={["/executions/e1"]}>
        <Routes>
          <Route path="/executions/:executionId" element={<ExecutionPage />} />
        </Routes>
      </MemoryRouter>
    </AuthProvider>,
  );
  return fetchImpl;
}

describe("ExecutionPage", () => {
  it("shows live progress, the waiting node, and approves from the page", async () => {
    const fetchImpl = renderPage();

    const panel = await screen.findByLabelText("승인 요청");
    await waitFor(() => expect(panel).toHaveTextContent("SEND_REPORT"));
    expect(screen.getByText("Send the report about runtimes?")).toBeInTheDocument();
    expect(screen.getAllByText("승인 대기").length).toBeGreaterThan(0);
    expect(screen.getByText("Compare runtimes")).toBeInTheDocument();
    expect(screen.getByText(/완료 · 2.0s/)).toBeInTheDocument();
    await waitFor(() => expect(document.querySelector(".dsl-node.status-waiting")).toHaveTextContent("report"));
    expect(document.querySelector(".dsl-node.status-completed")).toHaveTextContent("plan완료");

    fireEvent.click(screen.getByRole("button", { name: "승인" }));

    await waitFor(() =>
      expect(fetchImpl.mock.calls.some(([url]) => String(url) === "/api/v1/approvals/7/approve")).toBe(true),
    );
    const streamCall = fetchImpl.mock.calls.find(([url]) => String(url).endsWith("/stream"))!;
    expect((streamCall[1]!.headers as Record<string, string>).Authorization).toBe(`Basic ${btoa("admin:pw")}`);
  });
});
