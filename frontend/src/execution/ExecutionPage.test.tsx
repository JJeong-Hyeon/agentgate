import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import type { Execution } from "../api/types";
import { AuthProvider } from "../auth/AuthContext";
import { withMe } from "../testUtils/me";
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
  const fetchImpl = vi.fn(
    withMe(async (url, init) => {
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
    }),
  );
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

  it("shows what an agent asked for, how AgentGate decided, and what came back", async () => {
    const agentRun: Execution = {
      ...running,
      status: "COMPLETED",
      agentVersions: { "note-agent": 3 },
      nodes: [
        { taskId: "a0", nodeId: "plan", step: "plan", status: "COMPLETED", output: JSON.stringify({ agent: { kind: "start", prompt: "Save the note" } }) },
        {
          taskId: "a1",
          nodeId: "plan",
          step: "plan.think",
          status: "COMPLETED",
          output: JSON.stringify({
            agent: {
              kind: "tool_calls",
              step: 1,
              tokens: { input: 120, output: 15 },
              calls: [{ tool: "notes__save_note", arguments: { title: "standup" } }],
            },
          }),
        },
        {
          taskId: "a2",
          nodeId: "plan",
          step: "plan.gate",
          status: "COMPLETED",
          output: JSON.stringify({
            agent: {
              kind: "decision",
              tool: "plan:notes/save_note",
              status: "APPROVAL_REQUIRED",
              risk_level: "LOW",
              basis: "TOOL_REQUIRES_APPROVAL",
              approval_id: 9,
              arguments: { title: "standup" },
            },
          }),
        },
        { taskId: "a3", nodeId: "plan", step: "plan.gate", status: "COMPLETED", output: "{}" },
        {
          taskId: "a4",
          nodeId: "plan",
          step: "plan.think",
          status: "COMPLETED",
          output: JSON.stringify({ agent: { kind: "answer", step: 2, tokens: { input: 200, output: 30 }, answer: "Saved." } }),
        },
      ],
    };
    sessionStorage.setItem("agentgate.credentials", JSON.stringify({ username: "admin", password: "pw" }));
    const fetchImpl = vi.fn(
      withMe(async (url) => {
        if (url.endsWith("/stream")) return streamResponse([sse("snapshot", agentRun)]);
        if (url.includes("/versions/2")) return Response.json({ version: 2, dsl: DSL });
        return new Response("{}", { status: 404 });
      }),
    );
    render(
      <AuthProvider fetchImpl={fetchImpl as unknown as typeof fetch}>
        <MemoryRouter initialEntries={["/executions/e1"]}>
          <Routes>
            <Route path="/executions/:executionId" element={<ExecutionPage />} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>,
    );

    expect(await screen.findByText("Save the note")).toBeInTheDocument();
    expect(screen.getByText("note-agent v3")).toBeInTheDocument();
    expect(screen.getByText(/토큰 320 → 45/)).toBeInTheDocument();
    expect(screen.getByText(/Tool 호출 요청 \(턴 1\)/)).toBeInTheDocument();
    expect(screen.getByText("notes__save_note")).toBeInTheDocument();
    expect(screen.getByText("승인 필요")).toBeInTheDocument();
    expect(screen.getByText(/Agent 권한: 항상 승인/)).toBeInTheDocument();
    expect(screen.getByText(/승인 #9/)).toBeInTheDocument();
    expect(screen.getByText("Saved.")).toBeInTheDocument();
    // The empty hand-off step is not listed.
    expect(screen.getAllByText("plan.gate")).toHaveLength(1);
  });

  it("explains why a stopped execution ended", async () => {
    const stopped: Execution = { ...running, status: "STOPPED", error: "report: rejected by an approver", nodes: [] };
    sessionStorage.setItem("agentgate.credentials", JSON.stringify({ username: "admin", password: "pw" }));
    const fetchImpl = vi.fn(
      withMe(async (url) => {
        if (url.endsWith("/stream")) return streamResponse([sse("snapshot", stopped)]);
        if (url.includes("/versions/2")) return Response.json({ version: 2, dsl: DSL });
        return new Response("{}", { status: 404 });
      }),
    );
    render(
      <AuthProvider fetchImpl={fetchImpl as unknown as typeof fetch}>
        <MemoryRouter initialEntries={["/executions/e1"]}>
          <Routes>
            <Route path="/executions/:executionId" element={<ExecutionPage />} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>,
    );

    expect(await screen.findByText("중단됨")).toBeInTheDocument();
    expect(screen.getByText(/실행을 중단했습니다: report: rejected by an approver/)).toBeInTheDocument();
  });

  it("shows which agent ran a delegated step and who delegated it", async () => {
    const team: Execution = {
      ...running,
      status: "COMPLETED",
      agentVersions: { lead: 1, research: 2 },
      nodes: [
        {
          taskId: "d1",
          nodeId: "plan",
          step: "plan.think",
          status: "COMPLETED",
          output: JSON.stringify({
            agent: { kind: "tool_calls", step: 1, agent: "lead", calls: [{ tool: "delegate__research", arguments: { task: "find" } }] },
          }),
        },
        {
          taskId: "d2",
          nodeId: "plan",
          step: "research.execute",
          status: "COMPLETED",
          output: JSON.stringify({
            agent: {
              kind: "result",
              agent: "research",
              delegated_by: "lead",
              tool: "research:notes/save_note",
              status: "EXECUTED",
              content: "saved",
            },
          }),
        },
      ],
    };
    sessionStorage.setItem("agentgate.credentials", JSON.stringify({ username: "admin", password: "pw" }));
    const fetchImpl = vi.fn(
      withMe(async (url) => {
        if (url.endsWith("/stream")) return streamResponse([sse("snapshot", team)]);
        if (url.includes("/versions/2")) return Response.json({ version: 2, dsl: DSL });
        return new Response("{}", { status: 404 });
      }),
    );
    render(
      <AuthProvider fetchImpl={fetchImpl as unknown as typeof fetch}>
        <MemoryRouter initialEntries={["/executions/e1"]}>
          <Routes>
            <Route path="/executions/:executionId" element={<ExecutionPage />} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>,
    );

    expect(await screen.findByText("→ research에게 위임")).toBeInTheDocument();
    expect(screen.getByText("위임 경로 lead → research")).toBeInTheDocument();
    expect(screen.getByText("research v2")).toBeInTheDocument();
    expect(screen.getByText("saved")).toBeInTheDocument();
  });
});
