import { useCallback, useState } from "react";
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
        <h2>Workflows</h2>
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
            <h2>
              {detail.data.name} <span className="muted">v{detail.data.latestVersion}</span>
            </h2>
            <WorkflowPreview dsl={detail.data.dsl} />
          </>
        )}
      </section>
    </div>
  );
}
