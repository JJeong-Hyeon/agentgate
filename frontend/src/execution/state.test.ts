import type { Execution } from "../api/types";
import { applyUpdate, isFinished, duration, nodeStatuses } from "./state";

const base: Execution = {
  executionId: "e1",
  workflowId: "wf",
  workflowVersion: 1,
  task: "t",
  status: "RUNNING",
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
  nodes: [{ taskId: "t1", nodeId: "plan", step: "plan", status: "RUNNING" }],
};

describe("execution state", () => {
  it("upserts the touched node and takes the execution fields", () => {
    const next = applyUpdate(base, {
      event: { type: "NODE_COMPLETED" },
      execution: { ...base, nodes: [{ taskId: "t1", nodeId: "plan", step: "plan", status: "COMPLETED", output: "x" }] },
    });
    const added = applyUpdate(next, {
      event: { type: "NODE_STARTED" },
      execution: { ...base, nodes: [{ taskId: "t2", nodeId: "report", step: "report", status: "RUNNING" }] },
    });

    expect(next.nodes).toEqual([{ taskId: "t1", nodeId: "plan", step: "plan", status: "COMPLETED", output: "x" }]);
    expect(added.nodes!.map((n) => n.taskId)).toEqual(["t1", "t2"]);
  });

  it("keeps nodes on execution-level updates and fails running ones on failure", () => {
    const failed = applyUpdate(base, {
      event: { type: "EXECUTION_FAILED" },
      execution: { ...base, status: "FAILED", error: "boom", nodes: undefined },
    });

    expect(failed.status).toBe("FAILED");
    expect(failed.nodes![0]).toMatchObject({ status: "FAILED", error: "boom" });
  });

  it("uses the latest step per node", () => {
    const statuses = nodeStatuses([
      { taskId: "a", nodeId: "report", step: "report", status: "COMPLETED" },
      { taskId: "b", nodeId: "report", step: "report.approval", status: "WAITING" },
    ]);

    expect(statuses.get("report")).toBe("WAITING");
  });

  it("treats stopped executions as finished", () => {
    expect(isFinished("STOPPED")).toBe(true);
    expect(isFinished("WAITING_APPROVAL")).toBe(false);
  });

  it("formats durations", () => {
    const node = { taskId: "a", nodeId: "n", step: "n", status: "COMPLETED" as const };
    expect(duration({ ...node, startedAt: "2026-01-01T00:00:00Z", finishedAt: "2026-01-01T00:00:01.500Z" })).toBe("1.5s");
    expect(duration(node)).toBeNull();
  });
});
