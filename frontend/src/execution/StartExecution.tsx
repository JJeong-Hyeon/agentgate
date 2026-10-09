import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth, useClient } from "../auth/AuthContext";

/** Task input that starts a run of a saved workflow version and opens its execution view. */
export function StartExecution({ workflowId, version }: { workflowId: string; version?: number }) {
  const client = useClient();
  const { hasRole } = useAuth();
  const navigate = useNavigate();
  const [task, setTask] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);

  if (!hasRole("ADMIN", "EDITOR")) return null;

  async function start(event: FormEvent) {
    event.preventDefault();
    setStarting(true);
    setError(null);
    try {
      const execution = await client.startExecution(workflowId, task, version);
      navigate(`/executions/${execution.executionId}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setStarting(false);
    }
  }

  return (
    <form className="start-execution" onSubmit={start}>
      <input
        aria-label="작업"
        placeholder="실행할 작업을 입력하세요"
        value={task}
        onChange={(e) => setTask(e.target.value)}
      />
      <button type="submit" disabled={starting || !task.trim()}>
        {starting ? "시작 중…" : version ? `v${version} 실행` : "실행"}
      </button>
      {error && <span className="error">{error}</span>}
    </form>
  );
}
