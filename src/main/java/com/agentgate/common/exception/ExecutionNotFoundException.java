package com.agentgate.common.exception;

public class ExecutionNotFoundException extends RuntimeException {

    public ExecutionNotFoundException(String executionId) {
        super("Execution '%s' not found".formatted(executionId));
    }
}
