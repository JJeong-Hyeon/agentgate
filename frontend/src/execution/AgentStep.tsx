import { BASIS_TEXT, CALL_STATUS_TEXT, toolLabel, type AgentTrace, type Tokens } from "./agentTrace";

const json = (value: unknown) => JSON.stringify(value, null, 2);

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

/** One agent sub-step: what the model asked for, how AgentGate decided, what came back. */
export function AgentStep({ trace }: { trace: AgentTrace }) {
  switch (trace.kind) {
    case "start":
      return (
        <div className="agent-step">
          <span className="agent-step-kind">작업</span>
          <pre>{trace.prompt}</pre>
        </div>
      );
    case "tool_calls":
      return (
        <div className="agent-step">
          <span className="agent-step-kind">
            Tool 호출 요청 (턴 {trace.step})
            <TokenText tokens={trace.tokens} />
          </span>
          <ul>
            {trace.calls.map((call, i) => (
              <li key={i}>
                <span className="mono">{call.tool}</span>
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
          </span>
          {trace.arguments !== undefined && <pre>{json(trace.arguments)}</pre>}
          {trace.content && <pre>{trace.content}</pre>}
        </div>
      );
  }
}
