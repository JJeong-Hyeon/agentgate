import type { NodeExecution } from "../api/types";
import {
  agentTrace,
  callLabel,
  delegationPath,
  executionStats,
  isQuietAgentStep,
  toolLabel,
  totalTokens,
} from "./agentTrace";

const step = (stepName: string, output: unknown, status: NodeExecution["status"] = "COMPLETED"): NodeExecution => ({
  taskId: stepName,
  nodeId: stepName.split(".")[0],
  step: stepName,
  status,
  output: typeof output === "string" ? output : JSON.stringify(output),
});

describe("agent traces", () => {
  it("reads the agent summary from a step's output", () => {
    expect(agentTrace(step("a.think", { agent: { kind: "answer", answer: "hi" } }))).toEqual({
      kind: "answer",
      answer: "hi",
    });
    expect(agentTrace(step("plan", { plan: "1." }))).toBeNull();
    expect(agentTrace(step("plan", "not json"))).toBeNull();
  });

  it("adds up the tokens agents reported", () => {
    const nodes = [
      step("a.think", { agent: { kind: "tool_calls", calls: [], tokens: { input: 100, output: 10 } } }),
      step("a.think", { agent: { kind: "answer", answer: "x", tokens: { input: 150, output: 20 } } }),
      step("plan", { plan: "1." }),
    ];
    expect(totalTokens(nodes)).toEqual({ input: 250, output: 30 });
    expect(totalTokens([step("plan", { plan: "1." })])).toBeNull();
  });

  it("treats empty hand-offs between agent steps as quiet", () => {
    expect(isQuietAgentStep(step("a.gate", "{}"))).toBe(true);
    expect(isQuietAgentStep(step("a.approval", "{}", "WAITING"))).toBe(false);
    expect(isQuietAgentStep(step("plan", "{}"))).toBe(false);
  });

  it("labels tools without the node prefix", () => {
    expect(toolLabel("helper:notes/save_note")).toBe("notes/save_note");
  });

  it("reads memory tools as people would", () => {
    expect(toolLabel("helper:memory/recall")).toBe("기억 회상");
    expect(toolLabel("helper:memory/save")).toBe("기억 저장");
    expect(callLabel("memory_search")).toBe("기억 검색");
  });

  it("reads delegations as people would", () => {
    expect(toolLabel("lead:agent/research")).toBe("→ research에게 위임");
    expect(callLabel("delegate__research")).toBe("→ research에게 위임");
    expect(callLabel("notes__save_note")).toBe("notes__save_note");
    expect(delegationPath({ agent: "deep", delegated_by: "lead>research" })).toBe("lead → research → deep");
    expect(delegationPath({ agent: "lead" })).toBeNull();
  });
});

describe("execution stats", () => {
  const timed = (node: NodeExecution, startedAt: string, finishedAt: string): NodeExecution => ({
    ...node,
    startedAt,
    finishedAt,
  });

  it("counts LLM, Reviewer and tool nodes of a workflow without agent steps", () => {
    const nodes = [
      timed(step("draft", { draft: "초안" }), "2026-01-01T00:00:00Z", "2026-01-01T00:00:02Z"),
      timed(step("review", { review: "VERDICT: APPROVE", revisions: { review: 1 } }), "2026-01-01T00:00:02Z", "2026-01-01T00:00:03Z"),
      step("route", { route: "report" }),
      step("publish.gate", { pending_tool: null }),
      step("publish.execute", {
        publish: "ok",
        tool_results: [{ tool: "publish", status: "EXECUTED", duration_ms: 120 }],
      }),
      step("notify.execute", { tool_results: [{ tool: "notify", status: "FAILED", duration_ms: 30 }] }),
      step("approve", { approve: "APPROVED", tool_results: [{ tool: "approve", status: "REJECTED" }] }),
      step("check", {}),
    ];

    expect(executionStats(nodes)).toEqual({ llmCalls: 3, llmMs: 3000, toolRuns: 2, toolMs: 150 });
  });

  it("counts a market-report run (LLM → Agent → Reviewer → HTTP tool) as more than zero model calls", () => {
    const nodes = [
      timed(step("plan", { plan: "1." }), "2026-01-01T00:00:00Z", "2026-01-01T00:00:01Z"),
      step("research.think", { agent: { kind: "answer", answer: "x", llm: { model: "m", latency_ms: 400 } } }),
      timed(step("review", { review: "VERDICT: APPROVE" }), "2026-01-01T00:00:05Z", "2026-01-01T00:00:06Z"),
      step("send.execute", { send: "ok", tool_results: [{ tool: "send", status: "EXECUTED", duration_ms: 50 }] }),
    ];

    const stats = executionStats(nodes);
    expect(stats.llmCalls).toBe(3);
    expect(stats.llmMs).toBe(2400);
    expect(stats.toolRuns).toBe(1);
  });
});
