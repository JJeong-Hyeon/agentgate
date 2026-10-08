import type { NodeExecution } from "../api/types";
import { agentTrace, callLabel, delegationPath, isQuietAgentStep, toolLabel, totalTokens } from "./agentTrace";

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

  it("reads delegations as people would", () => {
    expect(toolLabel("lead:agent/research")).toBe("→ research에게 위임");
    expect(callLabel("delegate__research")).toBe("→ research에게 위임");
    expect(callLabel("notes__save_note")).toBe("notes__save_note");
    expect(delegationPath({ agent: "deep", delegated_by: "lead>research" })).toBe("lead → research → deep");
    expect(delegationPath({ agent: "lead" })).toBeNull();
  });
});
