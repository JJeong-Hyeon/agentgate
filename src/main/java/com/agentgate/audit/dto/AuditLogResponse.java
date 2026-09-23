package com.agentgate.audit.dto;

import com.agentgate.audit.domain.AuditLog;
import com.agentgate.risk.ActionStatus;
import com.agentgate.risk.RiskLevel;
import java.time.Instant;
import java.util.List;

public record AuditLogResponse(
        Long id,
        String agentId,
        String action,
        String target,
        List<String> labels,
        RiskLevel riskLevel,
        ActionStatus status,
        Long approvalId,
        Instant createdAt
) {
    public static AuditLogResponse from(AuditLog auditLog) {
        return new AuditLogResponse(
                auditLog.getId(),
                auditLog.getAgentId(),
                auditLog.getAction(),
                auditLog.getTarget(),
                auditLog.getLabels(),
                auditLog.getRiskLevel(),
                auditLog.getStatus(),
                auditLog.getApprovalId(),
                auditLog.getCreatedAt()
        );
    }
}
