// Pure editing operations on a workflow document; the builder UI is a thin layer over these.
import type { DslEdge, DslNode, NodeType, WorkflowDsl } from "../api/types";

export const NODE_ID_PATTERN = /^[A-Za-z][A-Za-z0-9_]{0,63}$/;
const RESERVED_IDS = new Set(["task", "workflow", "tool_results", "pending_tool", "revisions", "agent_runs", "stopped"]);

export const PALETTE: { type: NodeType; title: string; description: string }[] = [
  { type: "START", title: "Start", description: "시작 (1개)" },
  { type: "END", title: "End", description: "종료" },
  { type: "LLM", title: "LLM", description: "LLM 호출" },
  { type: "AGENT", title: "Agent", description: "등록된 Agent (Tool 사용) 또는 역할을 가진 LLM 단계" },
  { type: "ROUTER", title: "Router", description: "LLM이 경로 선택" },
  { type: "REVIEWER", title: "Reviewer", description: "APPROVE / REVISE 판정" },
  { type: "CONDITION", title: "Condition", description: "상태 값으로 분기" },
  { type: "HTTP_TOOL", title: "HTTP Tool", description: "AgentGate 검사 후 HTTP 호출" },
  { type: "MCP_TOOL", title: "MCP Tool", description: "AgentGate 검사 후 MCP 서버 도구 호출" },
  { type: "APPROVAL", title: "Approval", description: "사람의 승인 (거절 시 실행 종료)" },
];

export function defaultConfig(type: NodeType): Record<string, unknown> | undefined {
  switch (type) {
    case "LLM":
    case "AGENT":
      return { prompt: "{task}" };
    case "ROUTER":
      return { prompt: "{task}", routes: ["a", "b"] };
    case "REVIEWER":
      return { prompt: "Review: {task}", maxRevisions: 1 };
    case "CONDITION":
      return { key: "task", cases: { yes: "yes" }, default: "no" };
    case "HTTP_TOOL":
      return { action: "SEND_REPORT", url: "https://", method: "POST", labels: [], payloadKeys: ["task"] };
    case "MCP_TOOL":
      return { server: "", tool: "", arguments: {} };
    case "APPROVAL":
      return { message: "{task} 진행을 승인할까요?" };
    default:
      return undefined;
  }
}

/** Labels a branching node's outgoing edges must carry (mirrors the runtime validator); null otherwise. */
export function expectedLabels(node: DslNode): string[] | null {
  const config = node.config ?? {};
  switch (node.type) {
    case "ROUTER":
      return [...((config.routes as string[] | undefined) ?? [])];
    case "REVIEWER":
      return ["APPROVE", "REVISE"];
    case "CONDITION":
      return [...Object.keys((config.cases as Record<string, string> | undefined) ?? {}), String(config.default ?? "")];
    default:
      return null;
  }
}

export function nextNodeId(type: NodeType, existing: Iterable<string>): string {
  const taken = new Set(existing);
  const base = type.toLowerCase();
  if (type === "START" || type === "END") {
    if (!taken.has(base)) return base;
  }
  for (let n = 1; ; n++) {
    if (!taken.has(`${base}_${n}`)) return `${base}_${n}`;
  }
}

export function newNode(type: NodeType, existing: Iterable<string>, position: { x: number; y: number }): DslNode {
  const config = defaultConfig(type);
  return { id: nextNodeId(type, existing), type, position, ...(config ? { config } : {}) };
}

/** Why `id` cannot replace `current`, or null when it can. */
export function idProblem(id: string, current: string, nodes: DslNode[]): string | null {
  if (!NODE_ID_PATTERN.test(id)) return "영문자로 시작하고 영문/숫자/_만 사용할 수 있습니다.";
  if (RESERVED_IDS.has(id)) return `'${id}'는 예약어입니다.`;
  if (id !== current && nodes.some((n) => n.id === id)) return "이미 사용 중인 id입니다.";
  return null;
}

/** Renames a node and every edge and prompt variable that refers to it. */
export function renameNode(dsl: WorkflowDsl, from: string, to: string): WorkflowDsl {
  const swap = (id: string) => (id === from ? to : id);
  const variable = new RegExp(`(?<!\\{)\\{${from}\\}(?!\\})`, "g");
  const rewrite = (value: unknown): unknown => {
    if (typeof value === "string") return value.replace(variable, `{${to}}`);
    if (Array.isArray(value)) return value.map((v) => (v === from ? to : v));
    return value;
  };
  return {
    ...dsl,
    nodes: dsl.nodes.map((node) => ({
      ...node,
      id: swap(node.id),
      ...(node.config
        ? {
            config: Object.fromEntries(
              Object.entries(node.config).map(([key, value]) => [
                key,
                key === "key" && value === from ? to : rewrite(value),
              ]),
            ),
          }
        : {}),
    })),
    edges: dsl.edges.map((edge) => ({ ...edge, source: swap(edge.source), target: swap(edge.target) })),
  };
}

/** Label for a new edge: the first expected label of a branching source not yet used. */
export function labelForNewEdge(source: DslNode, edges: DslEdge[]): string | null {
  const expected = expectedLabels(source);
  if (!expected) return null;
  const used = new Set(edges.filter((e) => e.source === source.id).map((e) => e.label));
  return expected.find((label) => !used.has(label)) ?? expected[0] ?? null;
}

export function emptyWorkflow(): WorkflowDsl {
  return {
    nodes: [
      { id: "start", type: "START", position: { x: 0, y: 0 } },
      { id: "end", type: "END", position: { x: 480, y: 0 } },
    ],
    edges: [],
  };
}
