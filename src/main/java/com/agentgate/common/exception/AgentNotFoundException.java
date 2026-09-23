package com.agentgate.common.exception;

public class AgentNotFoundException extends RuntimeException {

    public AgentNotFoundException(String agentId) {
        super("Agent '%s' not found".formatted(agentId));
    }
}
