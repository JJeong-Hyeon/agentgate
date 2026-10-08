package com.agentgate.mcp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Registering or updating an MCP server. On update the name is ignored (it never changes), and
 * {@code headers} replaces all stored headers when present and keeps them when null.
 */
public record McpServerRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$") String name,
        @NotBlank @Size(max = 2000) @Pattern(regexp = "^https?://\\S+$", message = "must be an http(s) URL") String url,
        @Size(max = 1000) String description,
        Boolean enabled,
        @Size(max = 20) Map<@Pattern(regexp = "^[A-Za-z0-9-]{1,100}$") String, @Size(max = 4000) String> headers
) {
    public boolean enabledOrDefault() {
        return enabled == null || enabled;
    }
}
