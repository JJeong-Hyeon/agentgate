package com.agentgate.runtime;

import java.util.List;

/** One MCP server configured in the runtime, with its tools or the error listing them. */
public record McpServerTools(
        String server,
        String transport,
        // "runtime" (the runtime's config file) or "agentgate" (registered in AgentGate)
        String source,
        List<McpToolInfo> tools,
        String error
) {
}
