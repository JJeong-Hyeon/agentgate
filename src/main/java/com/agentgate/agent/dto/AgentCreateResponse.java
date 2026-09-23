package com.agentgate.agent.dto;

import java.time.Instant;

public record AgentCreateResponse(
        Long id,
        String agentId,
        String name,
        String apiKey,
        Instant createdAt
) {
}
