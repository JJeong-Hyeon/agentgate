import { useCallback, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import type { CreatedAgent } from "../api/types";
import { useClient } from "../auth/AuthContext";
import { useAsync } from "../useAsync";

const AGENT_ID_PATTERN = /^[a-z0-9][a-z0-9-]{0,63}$/;

export function AgentsPage() {
  const client = useClient();
  const agents = useAsync(useCallback(() => client.listAgents(), [client]));
  const [agentId, setAgentId] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [created, setCreated] = useState<CreatedAgent | null>(null);
  const [busy, setBusy] = useState(false);

  async function create(event: FormEvent) {
    event.preventDefault();
    if (!AGENT_ID_PATTERN.test(agentId)) {
      setError("Agent id는 소문자, 숫자, -만 쓸 수 있습니다 (최대 64자).");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      setCreated(await client.createAgent(agentId, name.trim() || agentId));
      setAgentId("");
      setName("");
      agents.reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="split">
      <section className="card list">
        <div className="row">
          <h2>Agents</h2>
          <button className="secondary" onClick={agents.reload}>
            새로고침
          </button>
        </div>
        {agents.error && <p className="error">{agents.error.message}</p>}
        {agents.data?.length === 0 && <p className="muted">등록된 Agent가 없습니다.</p>}
        <ul>
          {agents.data?.map((a) => (
            <li key={a.id}>
              <Link className="item" to={`/agents/${a.id}`}>
                <strong>{a.name}</strong>
                <span className="muted">
                  {a.agentId} · {a.latestDefinitionVersion > 0 ? `v${a.latestDefinitionVersion}` : "정의 없음"}
                  {a.maxRiskLevel && ` · 위험도 상한 ${a.maxRiskLevel}`}
                </span>
                {a.description && <span className="muted">{a.description}</span>}
              </Link>
            </li>
          ))}
        </ul>
      </section>
      <section className="card detail">
        <h2>새 Agent</h2>
        <p className="hint">
          Agent는 거버넌스 신원(정책, 위험도 상한, Audit)과 실행 정의(모델, 프롬프트, Tool 권한)를 함께 가집니다. 등록 후
          정의를 저장해야 워크플로에서 쓸 수 있습니다.
        </p>
        <form className="stack" onSubmit={create}>
          <label>
            Agent id
            <input value={agentId} onChange={(e) => setAgentId(e.target.value)} placeholder="research-agent" />
          </label>
          <label>
            이름
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Research Agent" />
          </label>
          {error && <p className="error">{error}</p>}
          <button type="submit" disabled={busy || !agentId}>
            등록
          </button>
        </form>
        {created && (
          <div className="notice" role="status">
            <p>
              <strong>{created.agentId}</strong>를 등록했습니다. 이 Agent가 직접 AgentGate를 호출할 때 쓰는 API Key는
              지금 한 번만 표시됩니다.
            </p>
            <code className="mono">{created.apiKey}</code>
            <p>
              <Link to={`/agents/${created.id}`}>정의 작성하기 →</Link>
            </p>
          </div>
        )}
      </section>
    </div>
  );
}
