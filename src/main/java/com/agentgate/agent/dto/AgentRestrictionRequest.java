package com.agentgate.agent.dto;

import com.agentgate.risk.RiskLevel;

public record AgentRestrictionRequest(RiskLevel maxRiskLevel) {
}
