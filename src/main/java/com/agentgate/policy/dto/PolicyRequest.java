package com.agentgate.policy.dto;

import com.agentgate.policy.domain.PolicyCategory;
import com.agentgate.risk.RiskLevel;
import jakarta.validation.constraints.NotNull;

public record PolicyRequest(
        String actionType,
        String label,
        @NotNull RiskLevel riskLevel,
        PolicyCategory category
) {
}
