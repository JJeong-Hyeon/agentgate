import { useState } from "react";
import type { RiskLevel, ToolRiskServer, ToolRiskTool } from "../api/types";
import { useAuth, useClient } from "../auth/AuthContext";
import type { AsyncState } from "../useAsync";

export const RISK_TEXT: Record<RiskLevel, string> = {
  LOW: "LOW · 허용",
  MEDIUM: "MEDIUM · 허용",
  HIGH: "HIGH · 승인 필요",
  BLOCKED: "BLOCKED · 차단",
};

const SOURCE_TEXT = { agentgate: "AgentGate 등록", runtime: "Runtime 설정 파일" };

/** Per-tool risk levels: what applies to calls today and what the server's hints suggest. */
export function ToolRisksSection({ risks, onRefresh }: { risks: AsyncState<ToolRiskServer[]>; onRefresh: () => void }) {
  const client = useClient();
  const { hasRole } = useAuth();
  const canEdit = hasRole("ADMIN");
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirmApply, setConfirmApply] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  async function run(key: string, action: () => Promise<unknown>) {
    setBusy(key);
    setError(null);
    try {
      await action();
      risks.reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(null);
    }
  }

  const unset = (risks.data ?? []).flatMap((s) => s.tools).filter((t) => t.riskLevel === null).length;

  return (
    <section className="card stack">
      <div className="row">
        <h2>Tool 위험도</h2>
        <span className="spacer" />
        <button className="secondary" onClick={onRefresh}>
          목록 새로고침
        </button>
        {canEdit && (confirmApply ? (
          <>
            <span className="muted">위험도가 없는 Tool {unset}개에 추천값을 적용합니다.</span>
            <button
              disabled={busy !== null}
              onClick={() =>
                run("apply", async () => {
                  const { applied } = await client.applyToolRiskSuggestions();
                  setNotice(`${applied}개 Tool에 추천 위험도를 적용했습니다.`);
                  setConfirmApply(false);
                })
              }
            >
              적용 확인
            </button>
            <button className="secondary" onClick={() => setConfirmApply(false)}>
              취소
            </button>
          </>
        ) : (
          <button disabled={unset === 0} onClick={() => setConfirmApply(true)}>
            추천값 일괄 적용
          </button>
        ))}
      </div>
      <p className="hint">
        위험도는 Tool 호출을 판정하는 정책(<span className="mono">MCP:서버:Tool</span>)으로 저장됩니다. 지정하지 않은
        Tool은 기본값 HIGH(승인 필요)입니다. 추천값은 서버가 스스로 알린 정보(읽기 전용 / 파괴적 여부)로 계산하므로
        확인 후 적용하세요. Agent별 권한(자동 / 항상 승인 / 차단)은 이 위험도보다 먼저 적용됩니다.
      </p>
      {notice && <p className="ok">{notice}</p>}
      {(risks.error || error) && <p className="error">{risks.error?.message ?? error}</p>}
      {risks.loading && !risks.data && <p className="muted">불러오는 중…</p>}
      {risks.data?.length === 0 && <p className="muted">Runtime에 MCP 서버가 없습니다.</p>}
      {risks.data?.map((server) => (
        <div key={`${server.server}:${server.source}`} className="tool-server">
          <h3>
            <span className="mono">{server.server}</span>{" "}
            <span className="muted">
              {server.source ? SOURCE_TEXT[server.source] : ""} · {server.transport}
            </span>
          </h3>
          {server.error && <p className="error">{server.error}</p>}
          {server.tools.length > 0 && (
            <table className="tool-table">
              <thead>
                <tr>
                  <th>Tool</th>
                  <th>위험도</th>
                  <th>추천</th>
                </tr>
              </thead>
              <tbody>
                {server.tools.map((tool) => (
                  <ToolRow
                    key={tool.name}
                    server={server.server}
                    tool={tool}
                    busy={busy === tool.action}
                    disabled={!canEdit}
                    onSet={(level) => run(tool.action, () => client.setToolRisk(server.server, tool.name, level))}
                    onClear={() => run(tool.action, () => client.clearToolRisk(server.server, tool.name))}
                  />
                ))}
              </tbody>
            </table>
          )}
        </div>
      ))}
    </section>
  );
}

function ToolRow(props: {
  server: string;
  tool: ToolRiskTool;
  busy: boolean;
  disabled: boolean;
  onSet: (level: RiskLevel) => void;
  onClear: () => void;
}) {
  const { tool, busy, disabled, onSet, onClear } = props;
  return (
    <tr className={tool.riskLevel ? "chosen" : undefined}>
      <td>
        <span className="mono">{tool.name}</span>
        {tool.description && <div className="muted">{tool.description}</div>}
      </td>
      <td>
        <select
          aria-label={`${tool.name} 위험도`}
          className={`risk-${tool.effectiveRiskLevel.toLowerCase()}`}
          disabled={busy || disabled}
          value={tool.riskLevel ?? ""}
          onChange={(e) => (e.target.value ? onSet(e.target.value as RiskLevel) : onClear())}
        >
          <option value="">기본값 (HIGH · 승인 필요)</option>
          {(Object.keys(RISK_TEXT) as RiskLevel[]).map((level) => (
            <option key={level} value={level}>
              {RISK_TEXT[level]}
            </option>
          ))}
        </select>
      </td>
      <td>
        {tool.suggestedRiskLevel && (
          <span className={`badge risk-${tool.suggestedRiskLevel.toLowerCase()}`}>{tool.suggestedRiskLevel}</span>
        )}
      </td>
    </tr>
  );
}
