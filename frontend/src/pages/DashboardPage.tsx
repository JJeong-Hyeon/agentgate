import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import type { Dashboard, DashboardBucket, DashboardPeriod } from "../api/types";
import { useClient } from "../auth/AuthContext";
import { formatMs } from "../execution/agentTrace";
import { useAsync } from "../useAsync";

const REFRESH_MS = 30_000;
const PERIODS: { value: DashboardPeriod; label: string }[] = [
  { value: "24h", label: "24시간" },
  { value: "7d", label: "7일" },
];

/** The first screen: what ran, what waits for people, what AgentGate stopped, and what the models cost. */
export function DashboardPage() {
  const client = useClient();
  const [period, setPeriod] = useState<DashboardPeriod>("24h");
  const dashboard = useAsync(useCallback(() => client.getDashboard(period), [client, period]));
  const { reload } = dashboard;

  useEffect(() => {
    const timer = setInterval(reload, REFRESH_MS);
    return () => clearInterval(timer);
  }, [reload]);

  const d = dashboard.data;
  return (
    <div className="dashboard">
      <section className="card row">
        <h2>대시보드</h2>
        <span className="spacer" />
        <div className="segmented" role="group" aria-label="기간">
          {PERIODS.map((p) => (
            <button
              key={p.value}
              className={p.value === period ? "active" : "secondary"}
              aria-pressed={p.value === period}
              onClick={() => setPeriod(p.value)}
            >
              {p.label}
            </button>
          ))}
        </div>
        <button className="secondary" onClick={reload}>
          새로고침
        </button>
      </section>
      {dashboard.error && <p className="card error">{dashboard.error.message}</p>}
      {d && <DashboardBody d={d} />}
    </div>
  );
}

function DashboardBody({ d }: { d: Dashboard }) {
  const e = d.executions;
  const g = d.governance;
  const a = d.approvals;
  return (
    <>
      <section className="tiles" aria-label="요약">
        <Tile label="실행" value={e.total}>
          <StatusLine icon="✓" tone="ok" label="완료" count={e.byStatus.COMPLETED ?? 0} />
          <StatusLine icon="■" tone="warn" label="중단" count={e.byStatus.STOPPED ?? 0} />
          <StatusLine icon="✕" tone="danger" label="실패" count={e.byStatus.FAILED ?? 0} />
        </Tile>
        <Tile label="지금" value={e.running + e.waitingApproval}>
          <div className="muted">진행 중 {e.running} · 승인 대기 {e.waitingApproval}</div>
        </Tile>
        <Tile label="평균 소요" value={e.avgDurationMs == null ? "–" : formatMs(e.avgDurationMs)}>
          <div className="muted">끝난 실행 기준</div>
        </Tile>
        <Tile label="승인 대기" value={a.pending}>
          <div className="muted">평균 결정 {a.avgWaitMs == null ? "–" : formatMs(a.avgWaitMs)}</div>
        </Tile>
        <Tile label="정책 차단율" value={`${(g.blockRate * 100).toFixed(1)}%`}>
          <div className="muted">
            판정 {g.total} · 차단 {g.blocked} · 승인 필요 {g.approvalRequired}
          </div>
        </Tile>
      </section>

      <section className="card stack">
        <h3>{d.period === "24h" ? "시간대별 실행" : "일별 실행"}</h3>
        <ExecutionsChart buckets={d.executions.buckets} hourly={d.period === "24h"} />
      </section>

      <div className="dashboard-grid">
        <section className="card stack">
          <h3>오래 기다리는 승인</h3>
          {a.oldestPending.length === 0 ? (
            <p className="muted">대기 중인 승인이 없습니다.</p>
          ) : (
            <ul className="plain-list">
              {a.oldestPending.map((p) => (
                <li key={p.id} className="row">
                  <span>
                    <span className={`badge risk-${p.riskLevel.toLowerCase()}`}>{p.riskLevel}</span>{" "}
                    <span className="mono">{p.action}</span> <span className="muted">{p.agentId}</span>
                  </span>
                  <span className="spacer" />
                  <span className="muted">{formatMs(Date.now() - new Date(p.createdAt).getTime())} 대기</span>
                  {p.executionId && <Link to={`/executions/${p.executionId}`}>실행</Link>}
                </li>
              ))}
            </ul>
          )}
          <Link to="/approvals">승인 화면으로 →</Link>
        </section>

        <section className="card stack">
          <h3>많이 차단된 행동</h3>
          {g.topBlocked.length === 0 ? (
            <p className="muted">차단된 행동이 없습니다.</p>
          ) : (
            <table className="governance-table">
              <tbody>
                {g.topBlocked.map((b) => (
                  <tr key={b.action}>
                    <td className="mono">{b.action}</td>
                    <td className="num">{b.count}건</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>

        <section className="card stack">
          <h3>모델 사용량</h3>
          {d.models.length === 0 ? (
            <p className="muted">이 기간에 Agent의 모델 호출이 없습니다.</p>
          ) : (
            <table className="governance-table">
              <thead>
                <tr>
                  <th>모델</th>
                  <th className="num">호출</th>
                  <th className="num">평균 응답</th>
                  <th className="num">토큰 (입력 → 출력)</th>
                </tr>
              </thead>
              <tbody>
                {d.models.map((m) => (
                  <tr key={m.model ?? "unknown"}>
                    <td className="mono">{m.model ?? "알 수 없음"}</td>
                    <td className="num">{m.calls}</td>
                    <td className="num">{m.avgLatencyMs == null ? "–" : formatMs(Math.round(m.avgLatencyMs))}</td>
                    <td className="num">
                      {m.inputTokens == null ? "–" : `${m.inputTokens.toLocaleString()} → ${(m.outputTokens ?? 0).toLocaleString()}`}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>

        <section className="card stack">
          <h3>최근 실패·중단</h3>
          {d.recentProblems.length === 0 ? (
            <p className="muted">문제가 된 실행이 없습니다.</p>
          ) : (
            <ul className="plain-list">
              {d.recentProblems.map((p) => (
                <li key={p.executionId}>
                  <div className="row">
                    <StatusLine
                      icon={p.status === "FAILED" ? "✕" : "■"}
                      tone={p.status === "FAILED" ? "danger" : "warn"}
                      label={p.status === "FAILED" ? "실패" : "중단"}
                    />
                    <Link to={`/executions/${p.executionId}`}>{p.workflowId}</Link>
                    <span className="spacer" />
                    <span className="muted">{new Date(p.createdAt).toLocaleString()}</span>
                  </div>
                  {p.error && <div className="muted truncate" title={p.error}>{p.error}</div>}
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </>
  );
}

function Tile({ label, value, children }: { label: string; value: number | string; children?: React.ReactNode }) {
  return (
    <div className="card tile">
      <div className="muted">{label}</div>
      <div className="tile-value">{value}</div>
      {children}
    </div>
  );
}

/** A state shown with an icon and a word, never color alone. */
function StatusLine({ icon, tone, label, count }: { icon: string; tone: string; label: string; count?: number }) {
  return (
    <span className="status-line">
      <span className={`status-icon tone-${tone}`} aria-hidden="true">
        {icon}
      </span>{" "}
      {label}
      {count !== undefined && ` ${count}`}
    </span>
  );
}

const CHART_HEIGHT = 140;
const BAR_GAP = 2;

/** Executions started per hour or day: one series, so no legend; hover a column for its breakdown. */
function ExecutionsChart({ buckets, hourly }: { buckets: DashboardBucket[]; hourly: boolean }) {
  const [hovered, setHovered] = useState<number | null>(null);
  const max = Math.max(1, ...buckets.map((b) => b.total));
  const width = 100 / buckets.length;
  const label = (b: DashboardBucket) =>
    hourly
      ? new Date(b.start).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })
      : new Date(b.start).toLocaleDateString([], { month: "short", day: "numeric" });
  const every = hourly ? 6 : 1;
  const shown = hovered == null ? null : buckets[hovered];

  return (
    <div className="chart" onMouseLeave={() => setHovered(null)}>
      <div className="chart-plot" style={{ height: CHART_HEIGHT }}>
        <span className="chart-max muted">{max}</span>
        <svg width="100%" height={CHART_HEIGHT} preserveAspectRatio="none" viewBox={`0 0 100 ${CHART_HEIGHT}`}>
          <line x1="0" x2="100" y1={CHART_HEIGHT - 0.5} y2={CHART_HEIGHT - 0.5} className="chart-axis" />
          <line x1="0" x2="100" y1="0.5" y2="0.5" className="chart-grid" />
          {buckets.map((b, i) => {
            const h = (b.total / max) * (CHART_HEIGHT - 4);
            return (
              <g key={b.start}>
                {b.total > 0 && (
                  <rect
                    className={`chart-bar${hovered === i ? " hovered" : ""}`}
                    x={i * width + BAR_GAP / 10}
                    width={Math.max(width - BAR_GAP / 5, 0.2)}
                    y={CHART_HEIGHT - h}
                    height={h}
                    rx={0.6}
                  />
                )}
                <rect
                  className="chart-hit"
                  x={i * width}
                  width={width}
                  y={0}
                  height={CHART_HEIGHT}
                  onMouseEnter={() => setHovered(i)}
                >
                  <title>{`${label(b)}: ${b.total}건`}</title>
                </rect>
              </g>
            );
          })}
        </svg>
        {shown && (
          <div
            className="chart-tooltip"
            role="status"
            style={{ left: `${Math.min((hovered! + 0.5) * width, 85)}%` }}
          >
            <strong>{label(shown)}</strong>
            <div>실행 {shown.total}건</div>
            <div className="muted">
              완료 {shown.completed} · 중단 {shown.stopped} · 실패 {shown.failed}
            </div>
          </div>
        )}
      </div>
      <div className="chart-labels muted">
        {buckets.map((b, i) => (
          <span key={b.start} style={{ width: `${width}%` }}>
            {i % every === 0 ? label(b) : ""}
          </span>
        ))}
      </div>
      <details>
        <summary className="muted">표로 보기</summary>
        <table className="governance-table">
          <thead>
            <tr>
              <th>{hourly ? "시각" : "날짜"}</th>
              <th className="num">실행</th>
              <th className="num">완료</th>
              <th className="num">중단</th>
              <th className="num">실패</th>
            </tr>
          </thead>
          <tbody>
            {buckets.map((b) => (
              <tr key={b.start}>
                <td>{label(b)}</td>
                <td className="num">{b.total}</td>
                <td className="num">{b.completed}</td>
                <td className="num">{b.stopped}</td>
                <td className="num">{b.failed}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </div>
  );
}
