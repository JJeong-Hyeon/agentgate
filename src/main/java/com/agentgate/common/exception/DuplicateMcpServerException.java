package com.agentgate.common.exception;

public class DuplicateMcpServerException extends RuntimeException {

    public DuplicateMcpServerException(String name) {
        super("MCP server '%s' already exists".formatted(name));
    }
}
