import { useCallback } from "react";
import { Link } from "react-router-dom";
import { useClient } from "../auth/AuthContext";
import { useAsync } from "../useAsync";

export function ExecutionsPage() {
  const client = useClient();
  const executions = useAsync(useCallback(() => client.listExecutions(), [client]));

  return (
    <section className="card">
      <div className="row">
        <h2>Executions</h2>
        <button className="secondary" onClick={executions.reload}>
          새로고침
        </button>
      </div>
      {executions.error && <p className="error">{executions.error.message}</p>}
      {executions.data?.length === 0 && <p className="muted">실행 기록이 없습니다.</p>}
      {!!executions.data?.length && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>상태</th>
                <th>워크플로</th>
                <th>작업</th>
                <th>시작</th>
              </tr>
            </thead>
            <tbody>
              {executions.data.map((e) => (
                <tr key={e.executionId}>
                  <td>
                    <Link to={`/executions/${e.executionId}`}>
                      <span className={`badge status-${e.status.toLowerCase()}`}>{e.status}</span>
                    </Link>
                  </td>
                  <td>
                    {e.workflowId} <span className="muted">v{e.workflowVersion}</span>
                  </td>
                  <td className="truncate">
                    <Link to={`/executions/${e.executionId}`}>{e.task}</Link>
                  </td>
                  <td className="muted">{new Date(e.createdAt).toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
