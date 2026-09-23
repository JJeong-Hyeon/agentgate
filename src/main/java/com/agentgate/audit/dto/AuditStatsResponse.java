package com.agentgate.audit.dto;

import com.agentgate.risk.ActionStatus;
import com.agentgate.risk.RiskLevel;
import java.util.Map;

public record AuditStatsResponse(
        long totalCount,
        Map<RiskLevel, Long> countByRiskLevel,
        Map<ActionStatus, Long> countByStatus,
        double blockRate
) {
}
