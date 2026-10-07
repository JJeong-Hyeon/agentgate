import { useCallback, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import type { Execution, NodeExecution } from "../api/types";
import { useAuth, useClient } from "../auth/AuthContext";
import { WorkflowPreview } from "../components/WorkflowPreview";
import { useAsync } from "../useAsync";
import { duration, nodeStatuses } from "./state";
import { useExecutionStream } from "./useExecutionStream";

const STATUS_TEXT: Record<string, string> = {
  RUNNING: "실행 중",
  WAITING_APPROVAL: "승인 대기",
  WAITING: "승인 대기",
  COMPLETED: "완료",
  FAILED: "실패",
};

export function ExecutionPage() {
  const { executionId } = useParams();
  const client = useClient();
  const { execution, error } = useExecutionStream(client, executionId!);

  if (!execution) return error ? <p className="error">{error}</p> : <p className="muted">불러오는 중…</p>;
  return (
    <div className="studio">
      <header className="card studio-header">
        <Link to="/executions" className="muted">
          ← 실행 목록
        </Link>
        <span className={`badge status-${execution.status.toLowerCase()}`}>{STATUS_TEXT[execution.status]}</span>
        <strong>
          <Link to={`/workflows/${execution.workflowId}/edit`}>{execution.workflowId}</Link>{" "}
          <span className="muted">v{execution.workflowVersion}</span>
        </strong>
        <span className="truncate" title={execution.task}>
          {execution.task}
        </span>
        <span className="spacer" />
        {error && <span className="error">연결 끊김, 재연결 중…</span>}
        <span className="muted">{new Date(execution.createdAt).toLocaleString()}</span>
      </header>
      {execution.status === "FAILED" && execution.error && <p className="card error">{execution.error}</p>}
      {execution.waitingApprovalId && <ApprovalPanel approvalId={execution.waitingApprovalId} />}
      <section className="card">
        <ExecutionGraph execution={execution} />
      </section>
      <section className="card">
        <h2>단계</h2>
        <Timeline nodes={execution.nodes ?? []} />
      </section>
    </div>
  );
}

function ExecutionGraph({ execution }: { execution: Execution }) {
  const client = useClient();
  const version = useAsync(
    useCallback(
      () => client.getWorkflowVersion(execution.workflowId, execution.workflowVersion),
      [client, execution.workflowId, execution.workflowVersion],
    ),
  );
  const statuses = useMemo(() => nodeStatuses(execution.nodes), [execution.nodes]);
  if (version.error) return <p className="error">{version.error.message}</p>;
  if (!version.data) return null;
  return <WorkflowPreview dsl={version.data.dsl} statuses={statuses} />;
}

function ApprovalPanel({ approvalId }: { approvalId: number }) {
  const client = useClient();
  const { username } = useAuth();
  const approval = useAsync(useCallback(() => client.getApproval(approvalId), [client, approvalId]));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function decide(decision: "approve" | "reject") {
    setBusy(true);
    setError(null);
    try {
      // The execution resumes on its own; the stream brings the result.
      await client.decideApproval(approvalId, decision, username ?? "admin");
      approval.reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  const a = approval.data;
  return (
    <section className="card approval-panel" aria-label="승인 요청">
      <div>
        <h2>승인이 필요합니다</h2>
        {a && (
          <p>
            <strong>{a.action}</strong> <span className={`badge risk-${a.riskLevel.toLowerCase()}`}>{a.riskLevel}</span>
            {a.target && <span className="muted"> → {a.target}</span>}
            {a.labels.length > 0 && <span className="muted"> · {a.labels.join(", ")}</span>}
          </p>
        )}
        {a?.reason && <p className="approval-reason">{a.reason}</p>}
        {a && a.status !== "PENDING" && <p className="muted">{a.status === "APPROVED" ? "승인됨" : "거절됨"} — 재개 중…</p>}
        {error && <p className="error">{error}</p>}
      </div>
      {(!a || a.status === "PENDING") && (
        <div className="actions">
          <button disabled={busy} onClick={() => decide("approve")}>
            승인
          </button>
          <button className="danger" disabled={busy} onClick={() => decide("reject")}>
            거절
          </button>
        </div>
      )}
    </section>
  );
}

function Timeline({ nodes }: { nodes: NodeExecution[] }) {
  if (nodes.length === 0) return <p className="muted">아직 실행된 단계가 없습니다.</p>;
  return (
    <ol className="timeline">
      {nodes.map((node) => (
        <li key={node.taskId} className={`step step-${node.status.toLowerCase()}`}>
          <div className="row">
            <span>
              <strong>{node.nodeId}</strong>
              {node.step !== node.nodeId && <span className="muted mono"> {node.step}</span>}
            </span>
            <span className="muted">
              {STATUS_TEXT[node.status] ?? node.status}
              {duration(node) && ` · ${duration(node)}`}
            </span>
          </div>
          {node.error && <pre className="error">{node.error}</pre>}
          {node.output && node.output !== "{}" && (
            <details>
              <summary>출력</summary>
              <pre>{prettyOutput(node.output)}</pre>
            </details>
          )}
        </li>
      ))}
    </ol>
  );
}

function prettyOutput(output: string): string {
  try {
    return JSON.stringify(JSON.parse(output), null, 2);
  } catch {
    return output;
  }
}
