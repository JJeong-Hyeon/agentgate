// Agent sub-step summaries the runtime reports as node output ({"agent": {...}}).
import type { NodeExecution } from "../api/types";

export interface Tokens {
  input?: number | null;
  output?: number | null;
}

export type AgentTrace =
  | { kind: "start"; prompt: string }
  | { kind: "tool_calls"; step?: number; tokens?: Tokens; calls: { tool: string; arguments: unknown }[] }
  | { kind: "answer"; step?: number; tokens?: Tokens; answer: string }
  | { kind: "repair"; step?: number; tokens?: Tokens; answer: string; problem: string }
  | {
      kind: "decision" | "result";
      tool: string;
      status: string;
      risk_level?: string | null;
      basis?: string | null;
      approval_id?: number | null;
      arguments?: unknown;
      content?: string;
    };

export function agentTrace(node: NodeExecution): AgentTrace | null {
  if (!node.output) return null;
  try {
    const parsed = JSON.parse(node.output) as { agent?: AgentTrace };
    return parsed && typeof parsed === "object" && parsed.agent ? parsed.agent : null;
  } catch {
    return null;
  }
}

/** Agent steps that only hand control back (no decision, no output) say nothing worth a row. */
export function isQuietAgentStep(node: NodeExecution): boolean {
  return node.status === "COMPLETED" && node.step.includes(".") && node.output === "{}";
}

export const BASIS_TEXT: Record<string, string> = {
  POLICY: "정책·위험도 판정",
  AGENT_RISK_CAP: "Agent 위험도 상한 초과",
  TOOL_NOT_GRANTED: "Agent에 허용되지 않은 Tool",
  TOOL_BLOCKED: "Agent 권한: 차단",
  TOOL_REQUIRES_APPROVAL: "Agent 권한: 항상 승인",
  APPROVAL_REQUESTED: "승인 요청",
};

export const CALL_STATUS_TEXT: Record<string, string> = {
  ALLOWED: "허용",
  APPROVAL_REQUIRED: "승인 필요",
  BLOCKED: "차단",
  REJECTED: "거절됨",
  EXECUTED: "실행됨",
  FAILED: "실패",
};

/** Tokens the run's agents used so far, or null when the model server reports none. */
export function totalTokens(nodes: NodeExecution[] = []): { input: number; output: number } | null {
  let input = 0;
  let output = 0;
  let seen = false;
  for (const node of nodes) {
    const trace = agentTrace(node);
    if (trace && "tokens" in trace && trace.tokens) {
      seen = true;
      input += trace.tokens.input ?? 0;
      output += trace.tokens.output ?? 0;
    }
  }
  return seen ? { input, output } : null;
}

/** "server/tool" from a recorded tool name like "helper:notes/save_note". */
export const toolLabel = (tool: string) => tool.slice(tool.indexOf(":") + 1);
