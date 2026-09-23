package com.agentgate.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentgate.audit.domain.AuditLog;
import com.agentgate.audit.dto.AuditLogResponse;
import com.agentgate.audit.repository.AuditLogRepository;
import com.agentgate.common.exception.AuditLogNotFoundException;
import com.agentgate.risk.ActionStatus;
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

    private AuditLogService service;

    @BeforeEach
    void setUp() {
        service = new AuditLogService(auditLogRepository);
    }

    @Test
    void recordSavesAuditLog() {
        service.record("mail-agent", "VIEW_DATA", null, List.of(), RiskLevel.LOW, ActionStatus.ALLOWED, null);

        verify(auditLogRepository).save(any(AuditLog.class));
    }

    @Test
    void getThrowsWhenMissing() {
        when(auditLogRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(99L)).isInstanceOf(AuditLogNotFoundException.class);
    }

    @Test
    void listFiltersByAgentIdStatusAndRiskLevel() {
        AuditLog match = new AuditLog("mail-agent", "SEND_EMAIL", null, List.of("PII"), RiskLevel.HIGH, ActionStatus.APPROVAL_REQUIRED, 1L);
        AuditLog other = new AuditLog("other-agent", "VIEW_DATA", null, List.of(), RiskLevel.LOW, ActionStatus.ALLOWED, null);
        when(auditLogRepository.findAll()).thenReturn(List.of(match, other));

        List<AuditLogResponse> result = service.list("mail-agent", ActionStatus.APPROVAL_REQUIRED, RiskLevel.HIGH);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).agentId()).isEqualTo("mail-agent");
    }

    @Test
    void listReturnsAllWhenNoFilters() {
        when(auditLogRepository.findAll()).thenReturn(List.of(
                new AuditLog("mail-agent", "VIEW_DATA", null, List.of(), RiskLevel.LOW, ActionStatus.ALLOWED, null)));

        List<AuditLogResponse> result = service.list(null, null, null);

        assertThat(result).hasSize(1);
    }
}
