import type { WorkflowDsl } from "../api/types";
import { expectedLabels, idProblem, labelForNewEdge, newNode, nextNodeId, renameNode } from "./model";

const dsl: WorkflowDsl = {
  nodes: [
    { id: "start", type: "START" },
    { id: "plan", type: "AGENT", config: { prompt: "Task: {task}" } },
    { id: "review", type: "REVIEWER", config: { prompt: "Check {plan}, not {{plan}}", maxRevisions: 1 } },
    { id: "send", type: "HTTP_TOOL", config: { action: "A", url: "http://x", payloadKeys: ["task", "plan"] } },
    { id: "check", type: "CONDITION", config: { key: "plan", cases: { yes: "Y" }, default: "no" } },
    { id: "end", type: "END" },
  ],
  edges: [
    { source: "start", target: "plan" },
    { source: "plan", target: "review" },
    { source: "review", target: "plan", label: "REVISE" },
  ],
};

describe("builder model", () => {
  it("generates unique ids per type", () => {
    expect(nextNodeId("LLM", ["llm_1", "llm_2"])).toBe("llm_3");
    expect(nextNodeId("START", [])).toBe("start");
    expect(nextNodeId("END", ["end"])).toBe("end_1");
  });

  it("creates nodes with type defaults", () => {
    expect(newNode("ROUTER", [], { x: 1, y: 2 })).toEqual({
      id: "router_1",
      type: "ROUTER",
      position: { x: 1, y: 2 },
      config: { prompt: "{task}", routes: ["a", "b"] },
    });
    expect(newNode("END", ["end"], { x: 0, y: 0 }).config).toBeUndefined();
    expect(newNode("MCP_TOOL", [], { x: 0, y: 0 })).toMatchObject({
      id: "mcp_tool_1",
      config: { server: "", tool: "", arguments: {} },
    });
  });

  it("validates node ids", () => {
    expect(idProblem("plan_2", "plan", dsl.nodes)).toBeNull();
    expect(idProblem("plan", "plan", dsl.nodes)).toBeNull();
    expect(idProblem("review", "plan", dsl.nodes)).toMatch("이미");
    expect(idProblem("task", "plan", dsl.nodes)).toMatch("예약어");
    expect(idProblem("2x", "plan", dsl.nodes)).toMatch("영문자");
  });

  it("renames edges, prompt variables, payload keys and condition keys", () => {
    const renamed = renameNode(dsl, "plan", "outline");
    const byId = Object.fromEntries(renamed.nodes.map((n) => [n.id, n]));

    expect(byId.outline).toBeDefined();
    expect(byId.review.config!.prompt).toBe("Check {outline}, not {{plan}}");
    expect(byId.send.config!.payloadKeys).toEqual(["task", "outline"]);
    expect(byId.check.config!.key).toBe("outline");
    expect(renamed.edges).toContainEqual({ source: "review", target: "outline", label: "REVISE" });
  });

  it("knows branch labels and picks the next unused one", () => {
    const review = dsl.nodes[2];
    expect(expectedLabels(review)).toEqual(["APPROVE", "REVISE"]);
    expect(expectedLabels(dsl.nodes[4])).toEqual(["yes", "no"]);
    expect(expectedLabels(dsl.nodes[1])).toBeNull();
    expect(labelForNewEdge(review, dsl.edges)).toBe("APPROVE");
    expect(labelForNewEdge(dsl.nodes[1], dsl.edges)).toBeNull();
  });
});
