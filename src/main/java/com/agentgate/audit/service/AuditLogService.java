package com.agentgate.audit.service;

import com.agentgate.audit.domain.AuditLog;
import com.agentgate.audit.dto.AuditLogResponse;
import com.agentgate.audit.repository.AuditLogRepository;
import com.agentgate.common.exception.AuditLogNotFoundException;
import com.agentgate.risk.ActionStatus;
import com.agentgate.risk.RiskLevel;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    @Transactional
    public void record(String agentId, String action, String target, List<String> labels,
                        RiskLevel riskLevel, ActionStatus status, Long approvalId) {
        auditLogRepository.save(new AuditLog(agentId, action, target, labels, riskLevel, status, approvalId));
    }

    public AuditLogResponse get(Long id) {
        return auditLogRepository.findById(id)
                .map(AuditLogResponse::from)
                .orElseThrow(() -> new AuditLogNotFoundException(id));
    }

    public List<AuditLogResponse> list(String agentId, ActionStatus status, RiskLevel riskLevel) {
        return auditLogRepository.findAll().stream()
                .filter(log -> agentId == null || Objects.equals(log.getAgentId(), agentId))
                .filter(log -> status == null || log.getStatus() == status)
                .filter(log -> riskLevel == null || log.getRiskLevel() == riskLevel)
                .map(AuditLogResponse::from)
                .toList();
    }
}
