import type { Agent, ToolPermission } from "../api/types";
import { PERMISSION_LABELS } from "./definitionForm";

interface DelegatesSectionProps {
  self: string | undefined;
  agents: Agent[] | null;
  chosen: Record<string, ToolPermission>;
  onChange: (delegates: Record<string, ToolPermission>) => void;
}

/** Other agents this one may hand work to, and whether that needs approval. */
export function DelegatesSection({ self, agents, chosen, onChange }: DelegatesSectionProps) {
  const others = (agents ?? []).filter((a) => a.agentId !== self);
  const unknown = Object.keys(chosen).filter((id) => !others.some((a) => a.agentId === id));

  const toggle = (agentId: string) => {
    const next = { ...chosen };
    if (next[agentId]) delete next[agentId];
    else next[agentId] = "AUTO";
    onChange(next);
  };

  return (
    <section className="card stack">
      <h3>위임할 수 있는 Agent</h3>
      <p className="hint">
        선택한 Agent에게 일을 맡길 수 있습니다. 맡은 Agent는 자기 정의·권한·이름으로 Tool을 실행하고, 그 기록에 위임
        경로가 남습니다. 위임 자체는 기본 허용이며, 여기서 "항상 승인" 또는 "차단"으로 바꿀 수 있습니다. 위임 순환과 3단계를
        넘는 위임은 저장할 수 없습니다.
      </p>
      {others.length === 0 && <p className="muted">다른 Agent가 없습니다.</p>}
      <table className="tool-table">
        <tbody>
          {others.map((agent) => {
            const permission = chosen[agent.agentId];
            const undefinedAgent = agent.latestDefinitionVersion === 0;
            return (
              <tr key={agent.id} className={permission ? "chosen" : undefined}>
                <td>
                  <label className="inline">
                    <input
                      type="checkbox"
                      aria-label={`${agent.agentId} 위임`}
                      checked={!!permission}
                      disabled={undefinedAgent && !permission}
                      onChange={() => toggle(agent.agentId)}
                    />
                    <strong>{agent.name}</strong> <span className="mono muted">{agent.agentId}</span>
                  </label>
                  {agent.description && <div className="muted">{agent.description}</div>}
                  {undefinedAgent && <div className="muted">정의가 없어 위임할 수 없습니다.</div>}
                </td>
                <td>
                  {permission && (
                    <select
                      aria-label={`${agent.agentId} 위임 권한`}
                      className={`permission-${permission.toLowerCase()}`}
                      value={permission}
                      onChange={(e) => onChange({ ...chosen, [agent.agentId]: e.target.value as ToolPermission })}
                    >
                      {(Object.keys(PERMISSION_LABELS) as ToolPermission[]).map((p) => (
                        <option key={p} value={p}>
                          {p === "AUTO" ? "자동 (기본 허용)" : PERMISSION_LABELS[p]}
                        </option>
                      ))}
                    </select>
                  )}
                </td>
              </tr>
            );
          })}
          {unknown.map((agentId) => (
            <tr key={agentId} className="chosen">
              <td>
                <span className="mono">{agentId}</span> <span className="error">없는 Agent</span>
              </td>
              <td>
                <button className="secondary" onClick={() => toggle(agentId)}>
                  빼기
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
