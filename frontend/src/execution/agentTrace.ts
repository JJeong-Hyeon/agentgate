// Agent sub-step summaries the runtime reports as node output ({"agent": {...}}).
import type { NodeExecution } from "../api/types";

export interface Tokens {
  input?: number | null;
  output?: number | null;
}

// Which agent ran the step, and who delegated to it (outermost first, ">"-separated).
export interface TraceAgent {
  agent?: string | null;
  delegated_by?: string | null;
}

// The model call behind a turn: which model answered and how long it took.
export interface LlmCall {
  model?: string | null;
  latency_ms?: number | null;
}

export type AgentTrace = TraceAgent &
  (
  | { kind: "start"; prompt: string; memories?: string[]; memory_status?: string }
  | {
      kind: "tool_calls";
      step?: number;
      tokens?: Tokens;
      llm?: LlmCall;
      calls: { tool: string; arguments: unknown }[];
    }
  | { kind: "answer"; step?: number; tokens?: Tokens; llm?: LlmCall; answer: string }
  | { kind: "repair"; step?: number; tokens?: Tokens; llm?: LlmCall; answer: string; problem: string }
  | {
      kind: "decision" | "result";
      tool: string;
      status: string;
      risk_level?: string | null;
      basis?: string | null;
      approval_id?: number | null;
      arguments?: unknown;
      content?: string;
      duration_ms?: number | null;
    }
  );

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

const MEMORY_LABELS: Record<string, string> = {
  "memory/search": "기억 검색",
  "memory/save": "기억 저장",
  "memory/recall": "기억 회상",
  memory_search: "기억 검색",
  memory_save: "기억 저장",
};

/** "server/tool" from a recorded tool name like "helper:notes/save_note"; delegations read "→ agent". */
export function toolLabel(tool: string): string {
  const name = tool.slice(tool.indexOf(":") + 1);
  if (MEMORY_LABELS[name]) return MEMORY_LABELS[name];
  return name.startsWith("agent/") ? `→ ${name.slice("agent/".length)}에게 위임` : name;
}

/** The model's function name as people read it: delegate__research → "→ research에게 위임". */
export function callLabel(functionName: string): string {
  if (MEMORY_LABELS[functionName]) return MEMORY_LABELS[functionName];
  return functionName.startsWith("delegate__") ? `→ ${functionName.slice("delegate__".length)}에게 위임` : functionName;
}

/** "lead → research" for a delegated step, or null when the agent ran as a workflow node. */
export function delegationPath(trace: TraceAgent): string | null {
  if (!trace.delegated_by) return null;
  return [...trace.delegated_by.split(">"), trace.agent ?? "?"].join(" → ");
}

export interface ExecutionStats {
  llmCalls: number;
  llmMs: number;
  toolRuns: number;
  toolMs: number;
}

function nodeOutput(node: NodeExecution): Record<string, unknown> | null {
  if (!node.output) return null;
  try {
    const parsed: unknown = JSON.parse(node.output);
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? (parsed as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

function elapsedMs(node: NodeExecution): number {
  if (!node.startedAt || !node.finishedAt) return 0;
  return Math.max(new Date(node.finishedAt).getTime() - new Date(node.startedAt).getTime(), 0);
}

/**
 * Model calls and tool runs of the run, with the time they took: the agents' own steps, plus the
 * workflow nodes that ask the model (LLM, Router, Reviewer: a step named after its node whose output
 * holds the answer under that name) and the tool results other nodes recorded.
 */
export function executionStats(nodes: NodeExecution[] = []): ExecutionStats {
  const stats = { llmCalls: 0, llmMs: 0, toolRuns: 0, toolMs: 0 };
  for (const node of nodes) {
    const trace = agentTrace(node);
    if (trace) {
      if ("llm" in trace && trace.llm) {
        stats.llmCalls += 1;
        stats.llmMs += trace.llm.latency_ms ?? 0;
      }
      if (trace.kind === "result" && trace.duration_ms != null) {
        stats.toolRuns += 1;
        stats.toolMs += trace.duration_ms;
      }
      continue;
    }
    const output = nodeOutput(node);
    if (!output) continue;
    const results = Array.isArray(output.tool_results) ? output.tool_results : [];
    if (results.length === 0 && !node.step.includes(".") && typeof output[node.nodeId] === "string") {
      stats.llmCalls += 1;
      stats.llmMs += elapsedMs(node);
    }
    for (const result of results as { status?: string; duration_ms?: number | null }[]) {
      if (result.status === "EXECUTED" || result.status === "FAILED") {
        stats.toolRuns += 1;
        stats.toolMs += result.duration_ms ?? 0;
      }
    }
  }
  return stats;
}

export function formatMs(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  return `${Math.floor(ms / 60_000)}m ${Math.round((ms % 60_000) / 1000)}s`;
}
