import { Background, Controls, ReactFlow, useNodesState } from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { useEffect, useMemo } from "react";
import type { WorkflowDsl } from "../api/types";
import { dslToFlow, type FlowNode } from "../dsl/flow";
import { nodeTypes } from "./DslNodeView";

export function WorkflowPreview({
  dsl,
  statuses,
}: {
  dsl: WorkflowDsl;
  // Execution status per node id, shown on the nodes.
  statuses?: Map<string, string>;
}) {
  const flow = useMemo(() => dslToFlow(dsl), [dsl]);
  // Kept in React Flow's node state so measured sizes survive status updates; replacing the node
  // objects would hide them until a re-measure that never comes (their DOM size does not change).
  const [nodes, setNodes, onNodesChange] = useNodesState<FlowNode>(flow.nodes);

  useEffect(() => setNodes(flow.nodes), [flow.nodes, setNodes]);
  useEffect(() => {
    setNodes((current) =>
      current.map((n) => {
        const status = statuses?.get(n.id);
        return n.data.status === status ? n : { ...n, data: { ...n.data, status } };
      }),
    );
  }, [statuses, setNodes, flow.nodes]);

  return (
    <div className="canvas" aria-label="Workflow graph">
      <ReactFlow
        nodes={nodes}
        edges={flow.edges}
        nodeTypes={nodeTypes}
        onNodesChange={onNodesChange}
        fitView
        nodesDraggable={false}
        nodesConnectable={false}
        elementsSelectable={false}
        proOptions={{ hideAttribution: true }}
      >
        <Background />
        <Controls showInteractive={false} />
      </ReactFlow>
    </div>
  );
}
