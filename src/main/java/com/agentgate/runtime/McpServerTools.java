package com.agentgate.runtime;

import java.util.List;

/** One MCP server configured in the runtime, with its tools or the error listing them. */
public record McpServerTools(String server, String transport, List<McpToolInfo> tools, String error) {
}
