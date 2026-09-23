package com.agentgate.agent.dto;

import com.agentgate.agent.domain.Agent;
import com.agentgate.risk.RiskLevel;
import java.time.Instant;

public record AgentResponse(
        Long id,
        String agentId,
        String name,
        RiskLevel maxRiskLevel,
        Instant createdAt
) {
    public static AgentResponse from(Agent agent) {
        return new AgentResponse(agent.getId(), agent.getAgentId(), agent.getName(),
                agent.getMaxRiskLevel(), agent.getCreatedAt());
    }
}
