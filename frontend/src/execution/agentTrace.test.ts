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
    startedAt: `2026-01-01T00:00:${startedAt}Z`,
    finishedAt: `2026-01-01T00:00:${finishedAt}Z`,
  });

  it("counts LLM, Reviewer and tool nodes, not only agents (market-report)", () => {
    const nodes = [
      timed(step("plan", { plan: "outline" }), "00", "02"),
      step("research", { agent: { kind: "start", prompt: "p", agent: "researcher" } }),
      step("research.think", { agent: { kind: "answer", answer: "a", llm: { latency_ms: 1500 } } }),
      timed(step("review", { review: "VERDICT: APPROVE", revisions: { review: 1 } }), "05", "08"),
      step("publish", { pending_tool: { status: "ALLOWED" } }),
      step("publish.execute", {
        pending_tool: null,
        tool_results: [{ tool: "publish", status: "EXECUTED", duration_ms: 120 }],
        publish: "ok",
      }),
    ];
    expect(executionStats(nodes)).toEqual({ llmCalls: 3, llmMs: 2000 + 1500 + 3000, toolRuns: 1, toolMs: 120 });
  });

  it("counts failed tool runs but not denied ones, and ignores unfinished model nodes", () => {
    const nodes = [
      step("fetch.execute", { tool_results: [{ status: "FAILED", duration_ms: 40 }], fetch: "x" }),
      step("send", { tool_results: [{ status: "BLOCKED" }], stopped: { node: "send" } }),
      step("approve", { approve: "APPROVED", tool_results: [{ status: "APPROVED" }] }),
      step("plan", "", "RUNNING"),
    ];
    expect(executionStats(nodes)).toEqual({ llmCalls: 0, llmMs: 0, toolRuns: 1, toolMs: 40 });
  });
});
