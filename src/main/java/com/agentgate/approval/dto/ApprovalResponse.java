package com.agentgate.approval.dto;

import com.agentgate.approval.domain.ApprovalRequest;
import com.agentgate.approval.domain.ApprovalStatus;
import com.agentgate.risk.RiskLevel;
import java.time.Instant;
import java.util.List;

public record ApprovalResponse(
        Long id,
        String agentId,
        String action,
        String target,
        List<String> labels,
        RiskLevel riskLevel,
        ApprovalStatus status,
        Instant createdAt,
        Instant decidedAt,
        String decidedBy,
        String executionId,
        String reason
) {
    public static ApprovalResponse from(ApprovalRequest approvalRequest) {
        return new ApprovalResponse(
                approvalRequest.getId(),
                approvalRequest.getAgentId(),
                approvalRequest.getAction(),
                approvalRequest.getTarget(),
                approvalRequest.getLabels(),
                approvalRequest.getRiskLevel(),
                approvalRequest.getStatus(),
                approvalRequest.getCreatedAt(),
                approvalRequest.getDecidedAt(),
                approvalRequest.getDecidedBy(),
                approvalRequest.getExecutionId(),
                approvalRequest.getReason()
        );
    }
}
