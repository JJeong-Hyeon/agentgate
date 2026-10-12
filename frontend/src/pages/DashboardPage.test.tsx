import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import type { Dashboard } from "../api/types";
import { AuthProvider } from "../auth/AuthContext";
import { withMe } from "../testUtils/me";
import { DashboardPage } from "./DashboardPage";

const hour = (n: number) => new Date(Date.UTC(2026, 9, 12, n)).toISOString();

function dashboard(period: "24h" | "7d"): Dashboard {
  return {
    period,
    since: hour(0),
    executions: {
      total: 12,
      byStatus: { RUNNING: 1, WAITING_APPROVAL: 1, COMPLETED: 8, STOPPED: 1, FAILED: 1 },
      running: 1,
      waitingApproval: 1,
      avgDurationMs: 8000,
      buckets: Array.from({ length: period === "24h" ? 24 : 7 }, (_, i) => ({
        start: hour(i % 24),
        total: i === 3 ? 5 : 0,
        completed: i === 3 ? 4 : 0,
        stopped: 0,
        failed: i === 3 ? 1 : 0,
      })),
    },
    approvals: {
      pending: 2,
      oldestPending: [
        { id: 7, agentId: "team-assistant", action: "MEMORY:SAVE", riskLevel: "MEDIUM", executionId: "e1", createdAt: hour(1) },
      ],
      decided: { APPROVED: 3, REJECTED: 1, EXPIRED: 0 },
      avgWaitMs: 17000,
    },
    governance: {
      total: 40,
      allowed: 30,
      approvalRequired: 6,
      blocked: 4,
      blockRate: 0.1,
      topBlocked: [{ action: "MCP:files:delete", count: 3 }],
    },
    models: [{ model: "qwen3:14b-q4_K_M", calls: 20, avgLatencyMs: 2712.4, inputTokens: 9000, outputTokens: 1200 }],
    recentProblems: [
      { executionId: "e9", workflowId: "assistant", status: "FAILED", error: "model timed out", createdAt: hour(5) },
    ],
  };
}

function renderPage() {
  sessionStorage.setItem("agentgate.credentials", JSON.stringify({ username: "admin", password: "pw" }));
  const fetchImpl = vi.fn(
    withMe(async (url) => {
      if (url.startsWith("/api/v1/dashboard")) return Response.json(dashboard(url.endsWith("7d") ? "7d" : "24h"));
      return new Response("{}", { status: 404 });
    }),
  );
  render(
    <AuthProvider fetchImpl={fetchImpl as unknown as typeof fetch}>
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>
    </AuthProvider>,
  );
  return fetchImpl;
}

describe("DashboardPage", () => {
  it("summarises runs, approvals, decisions, models and problems", async () => {
    renderPage();

    const tiles = await screen.findByLabelText("요약");
    expect(tiles).toHaveTextContent("실행12");
    expect(tiles).toHaveTextContent("완료 8");
    expect(tiles).toHaveTextContent("실패 1");
    expect(tiles).toHaveTextContent("평균 소요8.0s");
    expect(tiles).toHaveTextContent("승인 대기2");
    expect(tiles).toHaveTextContent("정책 차단율10.0%");
    expect(screen.getByText("MEMORY:SAVE")).toBeInTheDocument();
    expect(screen.getByText("MCP:files:delete")).toBeInTheDocument();
    expect(screen.getByText("qwen3:14b-q4_K_M")).toBeInTheDocument();
    expect(screen.getByText("2.7s")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "assistant" })).toHaveAttribute("href", "/executions/e9");
  });

  it("shows a column's breakdown on hover and switches period", async () => {
    const fetchImpl = renderPage();

    await screen.findByLabelText("요약");
    const hits = document.querySelectorAll(".chart-hit");
    expect(hits).toHaveLength(24);
    fireEvent.mouseEnter(hits[3]);
    expect(screen.getByRole("status")).toHaveTextContent("실행 5건");
    expect(screen.getByRole("status")).toHaveTextContent("완료 4 · 중단 0 · 실패 1");

    fireEvent.click(within(screen.getByRole("group", { name: "기간" })).getByRole("button", { name: "7일" }));
    await waitFor(() => expect(document.querySelectorAll(".chart-hit")).toHaveLength(7));
    expect(fetchImpl.mock.calls.some(([u]) => String(u) === "/api/v1/dashboard?period=7d")).toBe(true);
  });
});
