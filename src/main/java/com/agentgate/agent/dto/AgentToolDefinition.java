package com.agentgate.agent.dto;

import com.agentgate.agent.domain.ToolPermission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A tool the agent may call: a tool on an MCP server registered with the runtime.
 * AgentGate evaluates calls to it as action {@code MCP:<server>:<tool>}.
 */
public record AgentToolDefinition(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$") String server,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_.-]{1,128}$") String tool,
        @NotNull ToolPermission permission,
        @Size(max = 20) List<@NotBlank String> labels
) {
    public String action() {
        return "MCP:%s:%s".formatted(server, tool);
    }
}
