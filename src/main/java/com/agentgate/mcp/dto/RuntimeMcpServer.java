package com.agentgate.mcp.dto;

import java.util.Map;

/** An enabled MCP server with its credentials, for the runtime only. */
public record RuntimeMcpServer(String name, String url, Map<String, String> headers) {
}
