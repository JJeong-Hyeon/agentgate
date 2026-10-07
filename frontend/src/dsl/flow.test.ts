import researchJson from "../../../runtime/examples/research.json";
import type { WorkflowDsl } from "../api/types";
import { autoLayout, dslToFlow } from "./flow";

const research = researchJson as WorkflowDsl;

describe("autoLayout", () => {
  it("places nodes in columns by distance from START, ignoring loop-back edges", () => {
    const layout = autoLayout(research);

    expect(["start", "plan", "findings", "review", "report", "end"].map((id) => layout.get(id)!.x)).toEqual([
      0, 240, 480, 720, 960, 1200,
    ]);
  });

  it("stacks parallel nodes in the same column", () => {
    const layout = autoLayout({
      nodes: [
        { id: "start", type: "START" },
        { id: "a", type: "LLM" },
        { id: "b", type: "LLM" },
      ],
      edges: [
        { source: "start", target: "a" },
        { source: "start", target: "b" },
      ],
    });

    expect(layout.get("a")).toEqual({ x: 240, y: 0 });
    expect(layout.get("b")).toEqual({ x: 240, y: 110 });
  });
});

describe("dslToFlow", () => {
  it("maps nodes and labeled edges", () => {
    const { nodes, edges } = dslToFlow(research);

    expect(nodes.find((n) => n.id === "start")!.type).toBe("input");
    expect(nodes.find((n) => n.id === "end")!.type).toBe("output");
    expect(nodes.find((n) => n.id === "review")!.data.label).toBe("Reviewer");
    expect(nodes.find((n) => n.id === "report")!.className).toContain("node-http_tool");
    expect(edges.find((e) => e.label === "REVISE")).toMatchObject({ source: "review", target: "findings" });
  });

  it("keeps builder positions when present", () => {
    const { nodes } = dslToFlow({
      nodes: [{ id: "start", type: "START", position: { x: 5, y: 7 } }],
      edges: [],
    });

    expect(nodes[0].position).toEqual({ x: 5, y: 7 });
  });
});
