package com.agentgate.mcp.dto;

import java.time.Instant;
import java.util.List;

/** An MCP server as administrators see it: header names only, never their values. */
public record McpServerResponse(
        Long id,
        String name,
        String url,
        String description,
        boolean enabled,
        List<String> headerNames,
        Instant createdAt,
        Instant updatedAt
) {
}
