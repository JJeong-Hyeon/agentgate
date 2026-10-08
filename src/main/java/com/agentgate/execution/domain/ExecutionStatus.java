package com.agentgate.execution.domain;

public enum ExecutionStatus {
    RUNNING,
    WAITING_APPROVAL,
    COMPLETED,
    // A tool or approval was denied (rejected or blocked) and the workflow did not go on.
    STOPPED,
    FAILED
}
