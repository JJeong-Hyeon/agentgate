import { useCallback, useState } from "react";
import { useAuth, useClient } from "../auth/AuthContext";
import { useAsync } from "../useAsync";

export function ApprovalsPage() {
  const client = useClient();
  const { hasRole } = useAuth();
  const canDecide = hasRole("ADMIN", "APPROVER");
  const approvals = useAsync(useCallback(() => client.listApprovals("PENDING"), [client]));
  const [busy, setBusy] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function decide(id: number, decision: "approve" | "reject") {
    setBusy(id);
    setError(null);
    try {
      await client.decideApproval(id, decision);
      approvals.reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(null);
    }
  }

  return (
    <section className="card">
      <div className="row">
        <h2>승인 대기</h2>
        <button className="secondary" onClick={approvals.reload}>
          새로고침
        </button>
      </div>
      {(approvals.error || error) && <p className="error">{approvals.error?.message ?? error}</p>}
      {approvals.data?.length === 0 && <p className="muted">대기 중인 승인 요청이 없습니다.</p>}
      <ul className="approvals">
        {approvals.data?.map((a) => (
          <li key={a.id} className="approval">
            <div>
              <strong>{a.action}</strong> <span className={`badge risk-${a.riskLevel.toLowerCase()}`}>{a.riskLevel}</span>
              <div className="muted">
                {a.agentId}
                {a.target && ` → ${a.target}`}
                {a.labels.length > 0 && ` · ${a.labels.join(", ")}`}
              </div>
              {a.delegatedBy && (
                <div className="muted">위임 경로: {[...a.delegatedBy.split(">"), a.agentId].join(" → ")}</div>
              )}
              {a.reason && <div className="approval-reason">{a.reason}</div>}
              {a.executionId && <div className="muted mono">실행 {a.executionId}</div>}
            </div>
            {canDecide && (
              <div className="actions">
                <button disabled={busy === a.id} onClick={() => decide(a.id, "approve")}>
                  승인
                </button>
                <button className="danger" disabled={busy === a.id} onClick={() => decide(a.id, "reject")}>
                  거절
                </button>
              </div>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}
