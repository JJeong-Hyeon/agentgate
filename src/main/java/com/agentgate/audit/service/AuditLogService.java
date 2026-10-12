package com.agentgate.audit.service;

import com.agentgate.audit.domain.AuditLog;
import com.agentgate.audit.dto.AuditLogResponse;
import com.agentgate.audit.dto.AuditStatsResponse;
import com.agentgate.audit.repository.AuditLogRepository;
import com.agentgate.common.exception.AuditLogNotFoundException;
import com.agentgate.risk.ActionStatus;
import com.agentgate.risk.DecisionBasis;
import com.agentgate.risk.RiskLevel;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    /** Most entries a list returns; the console shows the newest. */
    public static final int MAX_LIMIT = 1000;
    public static final int DEFAULT_LIMIT = 200;

    @Transactional
    public void record(String agentId, String action, String target, List<String> labels,
                       RiskLevel riskLevel, ActionStatus status, Long approvalId, DecisionBasis basis,
                       String delegatedBy, String executionId) {
        auditLogRepository.save(new AuditLog(agentId, action, target, labels, riskLevel, status, approvalId, basis,
                delegatedBy, executionId));
    }

    public AuditLogResponse get(Long id) {
        return auditLogRepository.findById(id)
                .map(AuditLogResponse::from)
                .orElseThrow(() -> new AuditLogNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<AuditLogResponse> list(String agentId, String executionId, ActionStatus status, RiskLevel riskLevel,
                                       Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        return auditLogRepository.search(agentId, executionId, status, riskLevel, PageRequest.of(0, size)).stream()
                .map(AuditLogResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AuditStatsResponse stats(String agentId) {
        Map<RiskLevel, Long> byRiskLevel = new EnumMap<>(RiskLevel.class);
        auditLogRepository.countByRiskLevel(agentId).forEach(row -> byRiskLevel.put((RiskLevel) row[0], (Long) row[1]));
        Map<ActionStatus, Long> byStatus = new EnumMap<>(ActionStatus.class);
        auditLogRepository.countByStatus(agentId).forEach(row -> byStatus.put((ActionStatus) row[0], (Long) row[1]));
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        double blockRate = (total == 0) ? 0.0 : byRiskLevel.getOrDefault(RiskLevel.BLOCKED, 0L) / (double) total;

        return new AuditStatsResponse(total, byRiskLevel, byStatus, blockRate);
    }
}
