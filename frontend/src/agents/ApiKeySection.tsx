import { useState } from "react";
import type { Agent, IssuedApiKey } from "../api/types";
import { useClient } from "../auth/AuthContext";

/** Reissuing the agent's API key, confirmed by a second click; the new key is shown once. */
export function ApiKeySection({ agent, onReissued }: { agent: Agent; onReissued: () => void }) {
  const client = useClient();
  const [confirming, setConfirming] = useState(false);
  const [issued, setIssued] = useState<IssuedApiKey | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function reissue() {
    setBusy(true);
    setError(null);
    try {
      setIssued(await client.reissueApiKey(agent.id));
      setConfirming(false);
      onReissued();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card stack">
      <h3>API Key</h3>
      <p className="hint">
        이 Agent가 직접 AgentGate(<span className="mono">POST /api/v1/actions</span>)를 호출할 때 쓰는 키입니다. Agent
        Runtime으로 실행하는 경우에는 필요 없습니다.
        {agent.apiKeyIssuedAt && ` 현재 키 발급: ${new Date(agent.apiKeyIssuedAt).toLocaleString()}`}
      </p>
      {!confirming ? (
        <div>
          <button className="secondary" onClick={() => setConfirming(true)}>
            API Key 재발급
          </button>
        </div>
      ) : (
        <div className="row">
          <span className="error">기존 키는 즉시 사용할 수 없게 됩니다.</span>
          <button className="danger" disabled={busy} onClick={reissue}>
            재발급 확인
          </button>
          <button className="secondary" disabled={busy} onClick={() => setConfirming(false)}>
            취소
          </button>
        </div>
      )}
      {error && <p className="error">{error}</p>}
      {issued && (
        <div className="notice" role="status">
          <p>새 API Key입니다. 지금 한 번만 표시됩니다.</p>
          <code className="mono">{issued.apiKey}</code>
        </div>
      )}
    </section>
  );
}
