import researchJson from "../../../runtime/examples/research.json";
import type { WorkflowDsl } from "../api/types";
import { autoLayout, dslToFlow, flowToDsl } from "./flow";

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

    expect(nodes.every((n) => n.type === "dsl")).toBe(true);
    expect(nodes.find((n) => n.id === "review")!.data.dsl.label).toBe("Reviewer");
    expect(edges.find((e) => e.label === "REVISE")).toMatchObject({
      source: "review",
      target: "findings",
      sourceHandle: "back-out",
      targetHandle: "back-in",
    });
    expect(edges.find((e) => e.label === "APPROVE")!.sourceHandle).toBeNull();
  });

  it("keeps builder positions when present", () => {
    const { nodes } = dslToFlow({
      nodes: [{ id: "start", type: "START", position: { x: 5, y: 7 } }],
      edges: [],
    });

    expect(nodes[0].position).toEqual({ x: 5, y: 7 });
  });
});

describe("flowToDsl", () => {
  it("round-trips the research example, adding canvas positions", () => {
    const { nodes, edges } = dslToFlow(research);

    const back = flowToDsl(nodes, edges, { name: research.name });

    expect(back.edges).toEqual(research.edges.map((e) => ({ ...e })));
    expect(back.nodes.map(({ position: _p, ...n }) => n)).toEqual(research.nodes);
    expect(back.nodes.find((n) => n.id === "plan")!.position).toEqual({ x: 240, y: 0 });
    expect(back.name).toBe(research.name);
  });
});
