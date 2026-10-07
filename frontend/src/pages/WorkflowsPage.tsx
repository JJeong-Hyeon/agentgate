import { useCallback, useState } from "react";
import { Link } from "react-router-dom";
import { useClient } from "../auth/AuthContext";
import { WorkflowPreview } from "../components/WorkflowPreview";
import { useAsync } from "../useAsync";

export function WorkflowsPage() {
  const client = useClient();
  const [selected, setSelected] = useState<string | null>(null);
  const workflows = useAsync(useCallback(() => client.listWorkflows(), [client]));
  const detail = useAsync(
    useCallback(
      () => (selected ? client.getWorkflow(selected) : Promise.resolve(null)),
      [client, selected],
    ),
  );

  return (
    <div className="split">
      <section className="card list">
        <div className="row">
          <h2>Workflows</h2>
          <Link className="button" to="/workflows/new">
            새 워크플로
          </Link>
        </div>
        {workflows.error && <p className="error">{workflows.error.message}</p>}
        {workflows.data?.length === 0 && <p className="muted">저장된 워크플로가 없습니다.</p>}
        <ul>
          {workflows.data?.map((w) => (
            <li key={w.workflowId}>
              <button
                className={w.workflowId === selected ? "item selected" : "item"}
                onClick={() => setSelected(w.workflowId)}
              >
                <strong>{w.name}</strong>
                <span className="muted">
                  {w.workflowId} · v{w.latestVersion}
                </span>
              </button>
            </li>
          ))}
        </ul>
      </section>
      <section className="card detail">
        {!selected && <p className="muted">왼쪽에서 워크플로를 선택하세요.</p>}
        {detail.error && <p className="error">{detail.error.message}</p>}
        {detail.data?.dsl && (
          <>
            <div className="row">
              <h2>
                {detail.data.name} <span className="muted">v{detail.data.latestVersion}</span>
              </h2>
              <Link className="button" to={`/workflows/${detail.data.workflowId}/edit`}>
                편집
              </Link>
            </div>
            <WorkflowPreview dsl={detail.data.dsl} />
          </>
        )}
      </section>
    </div>
  );
}
