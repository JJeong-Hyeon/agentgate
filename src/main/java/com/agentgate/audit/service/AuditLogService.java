package com.agentgate.audit.service;

import com.agentgate.audit.domain.AuditLog;
import com.agentgate.audit.dto.AuditLogResponse;
import com.agentgate.audit.dto.AuditStatsResponse;
import com.agentgate.audit.repository.AuditLogRepository;
import com.agentgate.common.exception.AuditLogNotFoundException;
import com.agentgate.risk.ActionStatus;
import com.agentgate.risk.DecisionBasis;
import com.agentgate.risk.RiskLevel;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    @Transactional
    public void record(String agentId, String action, String target, List<String> labels,
                        RiskLevel riskLevel, ActionStatus status, Long approvalId, DecisionBasis basis,
                        String delegatedBy) {
        auditLogRepository.save(new AuditLog(agentId, action, target, labels, riskLevel, status, approvalId, basis,
                delegatedBy));
    }

    public AuditLogResponse get(Long id) {
        return auditLogRepository.findById(id)
                .map(AuditLogResponse::from)
                .orElseThrow(() -> new AuditLogNotFoundException(id));
    }

    public List<AuditLogResponse> list(String agentId, ActionStatus status, RiskLevel riskLevel) {
        return filterByAgentId(agentId)
                .filter(log -> status == null || log.getStatus() == status)
                .filter(log -> riskLevel == null || log.getRiskLevel() == riskLevel)
                .map(AuditLogResponse::from)
                .toList();
    }

    public AuditStatsResponse stats(String agentId) {
        List<AuditLog> logs = filterByAgentId(agentId).toList();

        Map<RiskLevel, Long> byRiskLevel = logs.stream()
                .collect(Collectors.groupingBy(AuditLog::getRiskLevel, Collectors.counting()));
        Map<ActionStatus, Long> byStatus = logs.stream()
                .collect(Collectors.groupingBy(AuditLog::getStatus, Collectors.counting()));
        long total = logs.size();
        double blockRate = (total == 0) ? 0.0 : byRiskLevel.getOrDefault(RiskLevel.BLOCKED, 0L) / (double) total;

        return new AuditStatsResponse(total, byRiskLevel, byStatus, blockRate);
    }

    private Stream<AuditLog> filterByAgentId(String agentId) {
        return auditLogRepository.findAll().stream()
                .filter(log -> agentId == null || Objects.equals(log.getAgentId(), agentId));
    }
}
