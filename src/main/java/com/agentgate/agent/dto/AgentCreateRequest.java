package com.agentgate.agent.dto;

import jakarta.validation.constraints.NotBlank;

public record AgentCreateRequest(
        @NotBlank String agentId,
        @NotBlank String name
) {
}
