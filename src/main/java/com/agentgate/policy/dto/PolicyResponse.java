package com.agentgate.policy.dto;

import com.agentgate.policy.domain.Policy;
import com.agentgate.policy.domain.PolicyCategory;
import com.agentgate.risk.RiskLevel;
import java.time.Instant;

public record PolicyResponse(
        Long id,
        String actionType,
        String label,
        RiskLevel riskLevel,
        PolicyCategory category,
        Instant createdAt
) {
    public static PolicyResponse from(Policy policy) {
        return new PolicyResponse(
                policy.getId(),
                policy.getActionType(),
                policy.getLabel(),
                policy.getRiskLevel(),
                policy.getCategory(),
                policy.getCreatedAt()
        );
    }
}
