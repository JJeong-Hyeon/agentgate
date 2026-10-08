package com.agentgate.common.exception;

public class McpServerNotFoundException extends RuntimeException {

    public McpServerNotFoundException(Long id) {
        super("MCP server '%d' not found".formatted(id));
    }
}
