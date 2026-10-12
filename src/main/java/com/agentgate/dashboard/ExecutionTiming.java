package com.agentgate.dashboard;

import com.agentgate.execution.domain.ExecutionStatus;
import java.time.Instant;

/** When an execution started and finished (null while it runs), and how it ended. */
public record ExecutionTiming(Instant createdAt, Instant finishedAt, ExecutionStatus status) {
}
