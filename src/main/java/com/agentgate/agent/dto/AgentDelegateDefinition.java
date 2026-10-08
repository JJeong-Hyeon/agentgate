package com.agentgate.agent.dto;

import com.agentgate.agent.domain.ToolPermission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Another agent this agent may hand work to. AgentGate evaluates each delegation as action
 * {@code AGENT:<agentId>} with this permission; the delegate then acts under its own definition.
 */
public record AgentDelegateDefinition(
        @NotBlank @Size(max = 255) String agentId,
        @NotNull ToolPermission permission
) {
    public static final String ACTION_PREFIX = "AGENT:";

    public String action() {
        return ACTION_PREFIX + agentId;
    }
}
