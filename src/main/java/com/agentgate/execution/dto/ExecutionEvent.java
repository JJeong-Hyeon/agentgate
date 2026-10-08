package com.agentgate.execution.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** Progress event reported by the agent runtime (see runtime/app/events.py). */
public record ExecutionEvent(
        @NotNull Type type,
        String nodeId,
        String step,
        String taskId,
        String output,
        String error,
        Long approvalId,
        Instant at
) {
    public enum Type {
        NODE_STARTED,
        NODE_COMPLETED,
        NODE_WAITING,
        NODE_FAILED,
        EXECUTION_WAITING,
        EXECUTION_COMPLETED,
        EXECUTION_STOPPED,
        EXECUTION_FAILED
    }

    public boolean isNodeEvent() {
        return type.name().startsWith("NODE_");
    }

    public Instant atOrNow() {
        return at != null ? at : Instant.now();
    }
}
