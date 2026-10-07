package com.agentgate.common.exception;

public class DuplicateWorkflowException extends RuntimeException {

    public DuplicateWorkflowException(String workflowId) {
        super("Workflow '%s' already exists".formatted(workflowId));
    }
}
