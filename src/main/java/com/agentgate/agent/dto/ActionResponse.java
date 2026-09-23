package com.agentgate.agent.dto;

import com.agentgate.risk.ActionStatus;
import com.agentgate.risk.RiskLevel;

public record ActionResponse(ActionStatus status, RiskLevel riskLevel, Long approvalId) {
}
