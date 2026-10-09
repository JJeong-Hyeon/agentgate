import {
  addEdge,
  Background,
  Controls,
  ReactFlow,
  ReactFlowProvider,
  useEdgesState,
  useNodesState,
  useReactFlow,
  type Connection,
  type Edge,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { useCallback, useMemo, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { ApiError } from "../api/client";
import type { DslNode, NodeType, WorkflowDsl } from "../api/types";
import { useAuth, useClient } from "../auth/AuthContext";
import { nodeTypes } from "../components/DslNodeView";
import { dslToFlow, edgeId, flowToDsl, routeEdges, type FlowNode } from "../dsl/flow";
import { useAsync } from "../useAsync";
import { StartExecution } from "../execution/StartExecution";
import { EdgeInspector, NodeInspector } from "./Inspector";
import { emptyWorkflow, labelForNewEdge, newNode, PALETTE, renameNode } from "./model";

const WORKFLOW_ID_PATTERN = /^[a-z0-9][a-z0-9-]{0,63}$/;

interface Issue {
  path: string;
  message: string;
  nodeId?: string | null;
}

export function BuilderPage() {
  const { workflowId } = useParams();
  const client = useClient();
  const loaded = useAsync(
    useCallback(
      () => (workflowId ? client.getWorkflow(workflowId) : Promise.resolve(null)),
      [client, workflowId],
    ),
  );

  if (workflowId && loaded.loading) return <p className="muted">불러오는 중…</p>;
  if (loaded.error) return <p className="error">{loaded.error.message}</p>;
  const dsl = loaded.data?.dsl ?? emptyWorkflow();
  return (
    <ReactFlowProvider>
      <Editor
        key={workflowId ?? "new"}
        initial={dsl}
        workflowId={workflowId ?? null}
        initialName={loaded.data?.name ?? ""}
        version={loaded.data?.latestVersion ?? 0}
      />
    </ReactFlowProvider>
  );
}

interface EditorProps {
  initial: WorkflowDsl;
  workflowId: string | null;
  initialName: string;
  version: number;
}

function Editor({ initial, workflowId, initialName, version: initialVersion }: EditorProps) {
  const client = useClient();
  const { hasRole } = useAuth();
  const canEdit = hasRole("ADMIN", "EDITOR");
  const navigate = useNavigate();
  const { screenToFlowPosition } = useReactFlow();
  const flow = useMemo(() => dslToFlow(initial), [initial]);
  const [nodes, setNodes, onNodesChange] = useNodesState<FlowNode>(flow.nodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>(flow.edges);
  const [selection, setSelection] = useState<{ node?: string; edge?: string }>({});
  const [newId, setNewId] = useState("");
  const [name, setName] = useState(initialName);
  const [version, setVersion] = useState(initialVersion);
  const [issues, setIssues] = useState<Issue[]>([]);
  const [message, setMessage] = useState<{ kind: "ok" | "error"; text: string } | null>(null);
  const [saving, setSaving] = useState(false);

  const dslNodes = useMemo(() => nodes.map((n) => n.data.dsl), [nodes]);
  const errorsByNode = useMemo(() => {
    const map = new Map<string, string>();
    for (const issue of issues) {
      if (issue.nodeId) map.set(issue.nodeId, [map.get(issue.nodeId), issue.message].filter(Boolean).join("\n"));
    }
    return map;
  }, [issues]);
  const shownNodes = useMemo(
    () =>
      nodes.map((n) => ({
        ...n,
        selected: n.id === selection.node,
        data: { ...n.data, error: errorsByNode.get(n.id) },
      })),
    [nodes, selection.node, errorsByNode],
  );
  const shownEdges = useMemo(
    () => routeEdges(nodes, edges).map((e) => ({ ...e, selected: e.id === selection.edge })),
    [nodes, edges, selection.edge],
  );

  /** Replaces the whole document, keeping canvas positions (they are part of the DSL). */
  const replaceDsl = useCallback(
    (dsl: WorkflowDsl) => {
      const next = dslToFlow(dsl);
      setNodes(next.nodes);
      setEdges(next.edges);
    },
    [setNodes, setEdges],
  );

  const onConnect = useCallback(
    (connection: Connection) => {
      const source = nodes.find((n) => n.id === connection.source)?.data.dsl;
      if (!source) return;
      const label = labelForNewEdge(source, flowToDsl(nodes, edges).edges);
      const edge = { ...connection, label: label ?? undefined };
      setEdges((current) =>
        current.some((e) => e.id === edgeId(edge))
          ? current
          : addEdge({ ...edge, id: edgeId(edge), type: "smoothstep" }, current),
      );
    },
    [nodes, edges, setEdges],
  );

  const addNode = (type: NodeType) => {
    const position = screenToFlowPosition({ x: window.innerWidth / 2, y: window.innerHeight / 2 });
    const dsl = newNode(type, nodes.map((n) => n.id), {
      x: Math.round(position.x + nodes.length * 12),
      y: Math.round(position.y + nodes.length * 12),
    });
    setNodes((current) => [...current, { id: dsl.id, type: "dsl", position: dsl.position!, data: { dsl } }]);
    setSelection({ node: dsl.id });
  };

  const updateNode = (dsl: DslNode) =>
    setNodes((current) => current.map((n) => (n.id === dsl.id ? { ...n, data: { ...n.data, dsl } } : n)));

  const rename = (from: string, to: string) => {
    replaceDsl(renameNode(flowToDsl(nodes, edges), from, to));
    setSelection({ node: to });
  };

  const deleteNode = (id: string) => {
    setNodes((current) => current.filter((n) => n.id !== id));
    setEdges((current) => current.filter((e) => e.source !== id && e.target !== id));
    setSelection({});
  };

  const setEdgeLabel = (id: string, label: string | null) => {
    const edge = edges.find((e) => e.id === id);
    if (!edge) return;
    const nextId = edgeId({ source: edge.source, target: edge.target, label });
    setEdges((current) => current.map((e) => (e.id === id ? { ...e, id: nextId, label: label ?? undefined } : e)));
    setSelection({ edge: nextId });
  };

  async function save() {
    const targetId = workflowId ?? newId;
    if (!WORKFLOW_ID_PATTERN.test(targetId)) {
      setMessage({ kind: "error", text: "워크플로 id는 소문자, 숫자, '-'만 사용할 수 있습니다." });
      return;
    }
    const dsl = flowToDsl(nodes, edges, name ? { name } : {});
    setSaving(true);
    setMessage(null);
    try {
      if (workflowId) {
        const saved = await client.addWorkflowVersion(workflowId, dsl);
        setVersion(saved.version);
        setMessage({ kind: "ok", text: `v${saved.version} 저장됨` });
      } else {
        await client.createWorkflow(targetId, dsl);
        navigate(`/workflows/${targetId}/edit`, { replace: true });
      }
      setIssues([]);
    } catch (e) {
      if (e instanceof ApiError && e.body?.errors?.length) {
        setIssues(e.body.errors);
        setMessage({ kind: "error", text: `검증 오류 ${e.body.errors.length}건` });
      } else {
        setMessage({ kind: "error", text: e instanceof Error ? e.message : String(e) });
      }
    } finally {
      setSaving(false);
    }
  }

  const selectedNode = dslNodes.find((n) => n.id === selection.node);
  const selectedEdge = edges.find((e) => e.id === selection.edge);

  return (
    <div className="builder">
      <header className="card builder-header">
        <Link to="/workflows" className="muted">
          ← 목록
        </Link>
        {workflowId ? (
          <strong>
            {workflowId} <span className="muted">v{version}</span>
          </strong>
        ) : (
          <input
            aria-label="워크플로 id"
            placeholder="워크플로 id (예: research)"
            value={newId}
            onChange={(e) => setNewId(e.target.value)}
          />
        )}
        <input aria-label="이름" placeholder="이름" value={name} onChange={(e) => setName(e.target.value)} />
        <span className="spacer" />
        {message && <span className={message.kind === "ok" ? "ok" : "error"}>{message.text}</span>}
        {workflowId && version > 0 && <StartExecution workflowId={workflowId} version={version} />}
        {canEdit && (
          <button onClick={save} disabled={saving}>
            {saving ? "저장 중…" : workflowId ? "새 버전 저장" : "만들기"}
          </button>
        )}
      </header>

      <aside className="card palette">
        <h3>노드</h3>
        {PALETTE.map((item) => (
          <button
            key={item.type}
            className="palette-item"
            title={item.description}
            disabled={item.type === "START" && dslNodes.some((n) => n.type === "START")}
            onClick={() => addNode(item.type)}
          >
            {item.title}
          </button>
        ))}
      </aside>

      <div className="canvas builder-canvas" aria-label="Workflow editor">
        <ReactFlow
          nodes={shownNodes}
          edges={shownEdges}
          nodeTypes={nodeTypes}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={onConnect}
          onNodeClick={(_, node) => setSelection({ node: node.id })}
          onEdgeClick={(_, edge) => setSelection({ edge: edge.id })}
          onPaneClick={() => setSelection({})}
          fitView
          proOptions={{ hideAttribution: true }}
        >
          <Background />
          <Controls />
        </ReactFlow>
      </div>

      <aside className="card inspector">
        {selectedNode && (
          <NodeInspector
            key={selectedNode.id}
            node={selectedNode}
            nodes={dslNodes}
            error={errorsByNode.get(selectedNode.id)}
            onChange={updateNode}
            onRename={(to) => rename(selectedNode.id, to)}
            onDelete={() => deleteNode(selectedNode.id)}
          />
        )}
        {selectedEdge && (
          <EdgeInspector
            edge={selectedEdge}
            source={dslNodes.find((n) => n.id === selectedEdge.source)}
            onLabel={(label) => setEdgeLabel(selectedEdge.id, label)}
            onDelete={() => {
              setEdges((current) => current.filter((e) => e.id !== selectedEdge.id));
              setSelection({});
            }}
          />
        )}
        {!selectedNode && !selectedEdge && (
          <p className="muted">노드나 연결을 선택하면 여기서 편집합니다. 노드 오른쪽 점을 끌어 다른 노드에 연결하세요.</p>
        )}
        {issues.length > 0 && (
          <div className="issues">
            <h3>검증 오류</h3>
            <ul>
              {issues.map((issue, i) => (
                <li key={i}>
                  {issue.nodeId ? (
                    <button className="link" onClick={() => setSelection({ node: issue.nodeId! })}>
                      {issue.nodeId}
                    </button>
                  ) : (
                    <span className="mono">{issue.path}</span>
                  )}
                  : {issue.message}
                </li>
              ))}
            </ul>
          </div>
        )}
      </aside>
    </div>
  );
}
