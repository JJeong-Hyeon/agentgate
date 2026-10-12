package com.agentgate.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentgate.audit.domain.AuditLog;
import com.agentgate.audit.dto.AuditLogResponse;
import com.agentgate.audit.dto.AuditStatsResponse;
import com.agentgate.audit.repository.AuditLogRepository;
import com.agentgate.common.exception.AuditLogNotFoundException;
import com.agentgate.observability.AgentGateMetrics;
import com.agentgate.risk.ActionStatus;
import com.agentgate.risk.DecisionBasis;
import com.agentgate.risk.RiskLevel;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private AgentGateMetrics metrics;

    private AuditLogService service;

    @BeforeEach
    void setUp() {
        service = new AuditLogService(auditLogRepository, metrics);
    }

    @Test
    void recordSavesAuditLog() {
        service.record("mail-agent", "VIEW_DATA", null, List.of(), RiskLevel.LOW, ActionStatus.ALLOWED, null, DecisionBasis.POLICY, null, "exec-1");

        verify(auditLogRepository).save(any(AuditLog.class));
        verify(metrics).decision(ActionStatus.ALLOWED, DecisionBasis.POLICY, RiskLevel.LOW);
    }

    @Test
    void getThrowsWhenMissing() {
        when(auditLogRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(99L)).isInstanceOf(AuditLogNotFoundException.class);
    }
}
