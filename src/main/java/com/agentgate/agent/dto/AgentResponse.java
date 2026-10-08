package com.agentgate.agent.dto;

import com.agentgate.agent.domain.Agent;
import com.agentgate.risk.RiskLevel;
import java.time.Instant;

public record AgentResponse(
        Long id,
        String agentId,
        String name,
        String description,
        RiskLevel maxRiskLevel,
        // 0 when no definition has been saved yet.
        int latestDefinitionVersion,
        Instant apiKeyIssuedAt,
        Instant createdAt
) {
    public static AgentResponse from(Agent agent) {
        return new AgentResponse(agent.getId(), agent.getAgentId(), agent.getName(), agent.getDescription(),
                agent.getMaxRiskLevel(), agent.getLatestDefinitionVersion(), agent.getApiKeyIssuedAt(), agent.getCreatedAt());
    }
}
