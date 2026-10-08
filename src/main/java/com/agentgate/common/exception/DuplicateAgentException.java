package com.agentgate.common.exception;

public class DuplicateAgentException extends RuntimeException {

    public DuplicateAgentException(String agentId) {
        super("Agent '%s' already exists".formatted(agentId));
    }
}
