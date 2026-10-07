package com.agentgate.common.exception;

public class WorkflowNotFoundException extends RuntimeException {

    public WorkflowNotFoundException(String message) {
        super(message);
    }
}
