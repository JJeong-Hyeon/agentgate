package com.agentgate.common.exception;

public class MemoryNotFoundException extends RuntimeException {

    public MemoryNotFoundException(String agentId, Long id) {
        super("Memory '%d' of agent '%s' not found".formatted(id, agentId));
    }
}
