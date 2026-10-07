import { useEffect, useState } from "react";
import type { AgentGateClient } from "../api/client";
import type { Execution } from "../api/types";
import { applyUpdate, isFinished, type ExecutionUpdate } from "./state";

const RETRY_MS = 2000;

/**
 * Live execution state: the stream's snapshot, then each update merged in. Reconnects (and
 * re-syncs from a fresh snapshot) until the execution finishes.
 */
export function useExecutionStream(client: AgentGateClient, executionId: string) {
  const [execution, setExecution] = useState<Execution | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    let finished = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    const connect = async () => {
      try {
        await client.streamExecution(
          executionId,
          (message) => {
            setError(null);
            if (message.event === "snapshot") {
              const snapshot = JSON.parse(message.data) as Execution;
              finished = isFinished(snapshot.status);
              setExecution(snapshot);
            } else if (message.event === "update") {
              const update = JSON.parse(message.data) as ExecutionUpdate;
              finished = isFinished(update.execution.status);
              setExecution((current) => (current ? applyUpdate(current, update) : current));
            }
          },
          controller.signal,
        );
      } catch (e) {
        if (controller.signal.aborted) return;
        setError(e instanceof Error ? e.message : String(e));
      }
      if (!finished && !controller.signal.aborted) timer = setTimeout(connect, RETRY_MS);
    };
    void connect();

    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [client, executionId]);

  return { execution, error };
}
