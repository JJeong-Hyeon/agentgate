import {
  BASIS_TEXT,
  CALL_STATUS_TEXT,
  callLabel,
  delegationPath,
  formatMs,
  toolLabel,
  type AgentTrace,
  type LlmCall,
  type Tokens,
} from "./agentTrace";

const json = (value: unknown) => JSON.stringify(value, null, 2);

function LlmText({ llm }: { llm?: LlmCall }) {
  if (!llm) return null;
  return (
    <span className="muted">
      {" "}
      · {llm.model ?? "모델"} {llm.latency_ms != null && formatMs(llm.latency_ms)}
    </span>
  );
}

function TokenText({ tokens }: { tokens?: Tokens }) {
  if (!tokens) return null;
  return (
    <span className="muted">
      {" "}
      · 토큰 {tokens.input ?? "?"} → {tokens.output ?? "?"}
    </span>
  );
}

function statusClass(status: string): string {
  if (status === "EXECUTED" || status === "ALLOWED") return "risk-low";
  if (status === "APPROVAL_REQUIRED") return "risk-high";
  return "risk-blocked";
}

function RecalledMemories({ memories, status }: { memories: string[]; status: string }) {
  if (status !== "EXECUTED") {
    return <p className="muted">기억 회상 안 함 ({CALL_STATUS_TEXT[status] ?? status})</p>;
  }
  if (memories.length === 0) return <p className="muted">회상한 기억 없음</p>;
  return (
    <>
      <span className="agent-step-kind">회상한 기억 {memories.length}개</span>
      <ul>
        {memories.map((memory, i) => (
          <li key={i}>{memory}</li>
        ))}
      </ul>
    </>
  );
}

/** One agent sub-step: what the model asked for, how AgentGate decided, what came back. */
export function AgentStep({ trace }: { trace: AgentTrace }) {
  const path = delegationPath(trace);
  return (
    <>
      {path && (
        <div className="agent-step-who">
          <span className="badge">{trace.agent}</span> <span className="muted">위임 경로 {path}</span>
        </div>
      )}
      <StepBody trace={trace} />
    </>
  );
}

function StepBody({ trace }: { trace: AgentTrace }) {
  switch (trace.kind) {
    case "start":
      return (
        <div className="agent-step">
          <span className="agent-step-kind">작업</span>
          <pre>{trace.prompt}</pre>
          {trace.memory_status && <RecalledMemories memories={trace.memories ?? []} status={trace.memory_status} />}
        </div>
      );
    case "tool_calls":
      return (
        <div className="agent-step">
          <span className="agent-step-kind">
            Tool 호출 요청 (턴 {trace.step})
            <LlmText llm={trace.llm} />
            <TokenText tokens={trace.tokens} />
          </span>
          <ul>
            {trace.calls.map((call, i) => (
              <li key={i}>
                <span className="mono">{callLabel(call.tool)}</span>
                <pre>{json(call.arguments)}</pre>
              </li>
            ))}
          </ul>
        </div>
      );
    case "answer":
      return (
        <div className="agent-step">
          <span className="agent-step-kind">
            최종 답변 (턴 {trace.step})
            <LlmText llm={trace.llm} />
            <TokenText tokens={trace.tokens} />
          </span>
          <pre>{trace.answer}</pre>
        </div>
      );
    case "repair":
      return (
        <div className="agent-step">
          <span className="agent-step-kind">
            출력 형식 재요청
            <LlmText llm={trace.llm} />
            <TokenText tokens={trace.tokens} />
          </span>
          <pre>{trace.answer}</pre>
          <p className="error">{trace.problem}</p>
        </div>
      );
    case "decision":
    case "result":
      return (
        <div className="agent-step">
          <span className="agent-step-kind">
            <span className="mono">{toolLabel(trace.tool)}</span>{" "}
            <span className={`badge ${statusClass(trace.status)}`}>{CALL_STATUS_TEXT[trace.status] ?? trace.status}</span>
            {trace.kind === "decision" && trace.basis && (
              <span className="muted"> · {BASIS_TEXT[trace.basis] ?? trace.basis}</span>
            )}
            {trace.risk_level && <span className="muted"> · 위험도 {trace.risk_level}</span>}
            {trace.approval_id && <span className="muted"> · 승인 #{trace.approval_id}</span>}
            {trace.duration_ms != null && <span className="muted"> · {formatMs(trace.duration_ms)}</span>}
          </span>
          {trace.arguments !== undefined && <pre>{json(trace.arguments)}</pre>}
          {trace.content && <pre>{trace.content}</pre>}
        </div>
      );
  }
}
