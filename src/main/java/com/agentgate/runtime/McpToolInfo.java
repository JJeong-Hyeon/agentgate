package com.agentgate.runtime;

import com.fasterxml.jackson.annotation.JsonAlias;
import tools.jackson.databind.JsonNode;

/** A tool an MCP server offers, as the runtime lists it (tools/list). */
public record McpToolInfo(
        String name,
        String title,
        String description,
        // JSON Schema of the tool's arguments.
        @JsonAlias("input_schema") JsonNode inputSchema,
        // Server hints such as readOnlyHint / destructiveHint; informational only.
        JsonNode annotations
) {
}
