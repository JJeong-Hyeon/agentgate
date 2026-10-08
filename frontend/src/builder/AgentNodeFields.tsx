import { useCallback } from "react";
import { Link } from "react-router-dom";
import { useClient } from "../auth/AuthContext";
import { PERMISSION_LABELS } from "../agents/definitionForm";
import { useAsync } from "../useAsync";

type Config = Record<string, unknown>;

interface AgentNodeFieldsProps {
  config: Config;
  onConfig: (config: Config) => void;
}

const INLINE_ONLY = ["system", "model", "temperature"];

/** AGENT node: run a registered agent (with its tools) or an inline LLM step. */
export function AgentNodeFields({ config, onConfig }: AgentNodeFieldsProps) {
  const client = useClient();
  const agents = useAsync(useCallback(() => client.listAgents(), [client]));
  const registered = typeof config.agentId === "string";
  const agentId = registered ? (config.agentId as string) : "";
  const pinned = config.agentVersion as number | undefined;
  const agent = agents.data?.find((a) => a.agentId === agentId);
  const definition = useAsync(
    useCallback(() => {
      if (!agent || agent.latestDefinitionVersion === 0) return Promise.resolve(null);
      return client.getAgentDefinitionVersion(agent.id, pinned ?? agent.latestDefinitionVersion);
    }, [client, agent, pinned]),
  );

  function setRegistered(on: boolean) {
    const next = { ...config };
    if (on) {
      INLINE_ONLY.forEach((k) => delete next[k]);
      next.agentId = agents.data?.find((a) => a.latestDefinitionVersion > 0)?.agentId ?? "";
    } else {
      delete next.agentId;
      delete next.agentVersion;
    }
    onConfig(next);
  }

  function pick(changes: Config) {
    const next = { ...config, ...changes };
    Object.entries(changes).forEach(([k, v]) => v === undefined && delete next[k]);
    onConfig(next);
  }

  return (
    <div className="stack">
      <fieldset className="mode">
        <label className="inline">
          <input type="radio" checked={registered} onChange={() => setRegistered(true)} />
          등록된 Agent (Tool 사용)
        </label>
        <label className="inline">
          <input type="radio" checked={!registered} onChange={() => setRegistered(false)} />
          인라인 LLM 단계
        </label>
      </fieldset>
      {registered && (
        <>
          <label>
            Agent
            <select
              value={agentId}
              onChange={(e) => pick({ agentId: e.target.value, agentVersion: undefined })}
            >
              <option value="">(선택)</option>
              {agents.data?.map((a) => (
                <option key={a.id} value={a.agentId} disabled={a.latestDefinitionVersion === 0}>
                  {a.name} ({a.agentId}){a.latestDefinitionVersion === 0 ? " · 정의 없음" : ""}
                </option>
              ))}
            </select>
          </label>
          {agents.error && <p className="error">{agents.error.message}</p>}
          {agent && agent.latestDefinitionVersion > 0 && (
            <label>
              정의 버전
              <select
                value={pinned ?? ""}
                onChange={(e) => pick({ agentVersion: e.target.value ? Number(e.target.value) : undefined })}
              >
                <option value="">실행 시점의 최신 (현재 v{agent.latestDefinitionVersion})</option>
                {Array.from({ length: agent.latestDefinitionVersion }, (_, i) => agent.latestDefinitionVersion - i).map(
                  (v) => (
                    <option key={v} value={v}>
                      v{v} 고정
                    </option>
                  ),
                )}
              </select>
            </label>
          )}
          <div className="field">
            <label>
              Agent에게 줄 작업
              <textarea
                rows={4}
                value={(config.prompt as string | undefined) ?? ""}
                onChange={(e) => pick({ prompt: e.target.value })}
              />
            </label>
            <span className="hint">{"{task}"}는 입력, {"{노드id}"}는 앞선 노드의 출력입니다.</span>
          </div>
          {definition.data?.definition && (
            <div className="agent-summary">
              <div className="row">
                <strong>v{definition.data.version}의 Tool 권한</strong>
                <span className="spacer" />
                {agent && <Link to={`/agents/${agent.id}`}>정의 편집 →</Link>}
              </div>
              {definition.data.definition.tools.length === 0 && <p className="muted">Tool 없음</p>}
              <ul>
                {definition.data.definition.tools.map((t) => (
                  <li key={`${t.server}/${t.tool}`}>
                    <span className="mono">
                      {t.server}/{t.tool}
                    </span>{" "}
                    <span className={`badge permission-${t.permission.toLowerCase()}`}>
                      {PERMISSION_LABELS[t.permission]}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </>
      )}
    </div>
  );
}
