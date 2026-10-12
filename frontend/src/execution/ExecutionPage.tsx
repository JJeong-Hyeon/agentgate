import { useCallback, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import type { Approval, AuditLog, Execution, NodeExecution } from "../api/types";
import { useAuth, useClient } from "../auth/AuthContext";
import { WorkflowPreview } from "../components/WorkflowPreview";
import { useAsync } from "../useAsync";
import { AgentStep } from "./AgentStep";
import {
  agentTrace,
  BASIS_TEXT,
  CALL_STATUS_TEXT,
  executionStats,
  formatMs,
  isQuietAgentStep,
  totalTokens,
} from "./agentTrace";
import { duration, nodeStatuses } from "./state";
import { useExecutionStream } from "./useExecutionStream";

const DECISION_TEXT: Record<string, string> = {
  APPROVED: "승인됨",
  REJECTED: "거절됨",
  EXPIRED: "만료됨",
};

const STATUS_TEXT: Record<string, string> = {
  RUNNING: "실행 중",
  WAITING_APPROVAL: "승인 대기",
  WAITING: "승인 대기",
  COMPLETED: "완료",
  STOPPED: "중단됨",
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
        <AgentSummary execution={execution} />
        {error && <span className="error">연결 끊김, 재연결 중…</span>}
        <span className="muted">{new Date(execution.createdAt).toLocaleString()}</span>
      </header>
      {execution.status === "FAILED" && execution.error && <p className="card error">{execution.error}</p>}
      {execution.status === "STOPPED" && (
        <p className="card stopped-reason">
          거절되거나 차단된 단계가 있어 실행을 중단했습니다{execution.error && `: ${execution.error}`}
        </p>
      )}
      {execution.waitingApprovalId && <ApprovalPanel approvalId={execution.waitingApprovalId} />}
      <section className="card">
        <ExecutionGraph execution={execution} />
      </section>
      <ExecutionInsights execution={execution} />
      <section className="card">
        <h2>단계</h2>
        <Timeline nodes={execution.nodes ?? []} />
      </section>
    </div>
  );
}

function AgentSummary({ execution }: { execution: Execution }) {
  const tokens = useMemo(() => totalTokens(execution.nodes), [execution.nodes]);
  const versions = Object.entries(execution.agentVersions ?? {});
  if (versions.length === 0) return null;
  return (
    <span className="muted agent-versions">
      {versions.map(([agentId, version]) => (
        <span key={agentId} className="badge">
          {agentId} v{version}
        </span>
      ))}
      {tokens && ` 토큰 ${tokens.input} → ${tokens.output}`}
    </span>
  );
}

/** Where the run's time went and what AgentGate decided along the way. */
function ExecutionInsights({ execution }: { execution: Execution }) {
  const client = useClient();
  const nodes = execution.nodes ?? [];
  // Refetched as the run progresses: each new step may come with new decisions.
  const progress = `${execution.status}:${nodes.length}`;
  const governance = useAsync(
    useCallback(
      () =>
        Promise.all([
          client.listExecutionAuditLogs(execution.executionId),
          client.listApprovals(undefined, execution.executionId),
        ]),
      [client, execution.executionId, progress],
    ),
  );
  const stats = useMemo(() => executionStats(nodes), [nodes]);
  const tokens = useMemo(() => totalTokens(nodes), [nodes]);
  const [audits, approvals] = governance.data ?? [[], []];
  const end = execution.finishedAt ? new Date(execution.finishedAt).getTime() : Date.now();
  const elapsed = end - new Date(execution.createdAt).getTime();
  const counts = { ALLOWED: 0, APPROVAL_REQUIRED: 0, BLOCKED: 0 };
  for (const audit of audits) counts[audit.status] += 1;

  return (
    <section className="card stack" aria-label="실행 분석">
      <h2>실행 분석</h2>
      <dl className="insights">
        <Metric label={execution.finishedAt ? "총 소요" : "경과"} value={formatMs(Math.max(elapsed, 0))} />
        <Metric label="LLM 호출" value={`${stats.llmCalls}회 · ${formatMs(stats.llmMs)}`} />
        {tokens && <Metric label="토큰 (입력 → 출력)" value={`${tokens.input} → ${tokens.output}`} />}
        <Metric label="Tool 실행" value={`${stats.toolRuns}회 · ${formatMs(stats.toolMs)}`} />
        <Metric
          label="판정 (허용 / 승인 필요 / 차단)"
          value={`${counts.ALLOWED} / ${counts.APPROVAL_REQUIRED} / ${counts.BLOCKED}`}
        />
        <Metric label="승인 대기" value={approvalWait(approvals)} />
      </dl>
      {governance.error && <p className="error">{governance.error.message}</p>}
      <GovernanceTable audits={audits} approvals={approvals} />
    </section>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="muted">{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

/** Total time approvals of this run waited for a decision (still-pending ones count until now). */
function approvalWait(approvals: Approval[]): string {
  if (approvals.length === 0) return "없음";
  let ms = 0;
  for (const a of approvals) {
    const decided = a.decidedAt ? new Date(a.decidedAt).getTime() : Date.now();
    ms += Math.max(decided - new Date(a.createdAt).getTime(), 0);
  }
  const pending = approvals.filter((a) => a.status === "PENDING").length;
  return `${approvals.length}건 · ${formatMs(ms)}${pending ? ` (대기 중 ${pending})` : ""}`;
}

function GovernanceTable({ audits, approvals }: { audits: AuditLog[]; approvals: Approval[] }) {
  if (audits.length === 0) return <p className="muted">아직 거버넌스 판정이 없습니다.</p>;
  const byId = new Map(approvals.map((a) => [a.id, a]));
  return (
    <table className="governance-table">
      <thead>
        <tr>
          <th>시각</th>
          <th>Agent</th>
          <th>행동</th>
          <th>판정</th>
          <th>근거</th>
          <th>승인</th>
        </tr>
      </thead>
      <tbody>
        {[...audits].reverse().map((audit) => {
          const approval = audit.approvalId ? byId.get(audit.approvalId) : undefined;
          return (
            <tr key={audit.id}>
              <td className="muted">{new Date(audit.createdAt).toLocaleTimeString()}</td>
              <td>
                {audit.agentId}
                {audit.delegatedBy && <div className="muted">위임: {audit.delegatedBy.split(">").join(" → ")}</div>}
              </td>
              <td className="mono">{audit.action}</td>
              <td>
                <span className={`badge risk-${audit.riskLevel.toLowerCase()}`}>{audit.riskLevel}</span>{" "}
                {CALL_STATUS_TEXT[audit.status] ?? audit.status}
              </td>
              <td className="muted">{audit.basis ? BASIS_TEXT[audit.basis] ?? audit.basis : ""}</td>
              <td>{approval ? approvalText(approval) : audit.approvalId ? `#${audit.approvalId}` : ""}</td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

function approvalText(approval: Approval): string {
  const status = DECISION_TEXT[approval.status] ?? "대기 중";
  if (!approval.decidedAt) return `#${approval.id} ${status}`;
  const waited = new Date(approval.decidedAt).getTime() - new Date(approval.createdAt).getTime();
  return `#${approval.id} ${status} (${approval.decidedBy ?? "?"}, ${formatMs(Math.max(waited, 0))})`;
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
  const { hasRole } = useAuth();
  const canDecide = hasRole("ADMIN", "APPROVER");
  const approval = useAsync(useCallback(() => client.getApproval(approvalId), [client, approvalId]));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function decide(decision: "approve" | "reject") {
    setBusy(true);
    setError(null);
    try {
      // The execution resumes on its own; the stream brings the result.
      await client.decideApproval(approvalId, decision);
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
        {a?.delegatedBy && (
          <p className="muted">
            위임 경로: {[...a.delegatedBy.split(">"), a.agentId].join(" → ")}
          </p>
        )}
        {a?.reason && <p className="approval-reason">{a.reason}</p>}
        {a && a.status !== "PENDING" && <p className="muted">{DECISION_TEXT[a.status]} — 재개 중…</p>}
        {error && <p className="error">{error}</p>}
      </div>
      {canDecide && (!a || a.status === "PENDING") && (
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
      {nodes.filter((node) => !isQuietAgentStep(node)).map((node) => (
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
          {agentTrace(node) ? (
            <AgentStep trace={agentTrace(node)!} />
          ) : (
            node.output &&
            node.output !== "{}" && (
              <details>
                <summary>출력</summary>
                <pre>{prettyOutput(node.output)}</pre>
              </details>
            )
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
