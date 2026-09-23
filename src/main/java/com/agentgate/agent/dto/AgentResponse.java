package com.agentgate.agent.dto;

import com.agentgate.agent.domain.Agent;
import java.time.Instant;

public record AgentResponse(
        Long id,
        String agentId,
        String name,
        Instant createdAt
) {
    public static AgentResponse from(Agent agent) {
        return new AgentResponse(agent.getId(), agent.getAgentId(), agent.getName(), agent.getCreatedAt());
    }
}
