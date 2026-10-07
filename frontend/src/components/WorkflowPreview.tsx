import { Background, Controls, ReactFlow } from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { useMemo } from "react";
import type { WorkflowDsl } from "../api/types";
import { dslToFlow } from "../dsl/flow";
import { nodeTypes } from "./DslNodeView";

export function WorkflowPreview({ dsl }: { dsl: WorkflowDsl }) {
  const { nodes, edges } = useMemo(() => dslToFlow(dsl), [dsl]);
  return (
    <div className="canvas" aria-label="Workflow graph">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
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
