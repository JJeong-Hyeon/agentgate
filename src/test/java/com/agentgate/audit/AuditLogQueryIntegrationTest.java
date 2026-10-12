package com.agentgate.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentgate.audit.domain.AuditLog;
import com.agentgate.audit.dto.AuditLogResponse;
import com.agentgate.audit.dto.AuditStatsResponse;
import com.agentgate.audit.repository.AuditLogRepository;
import com.agentgate.audit.service.AuditLogService;
import com.agentgate.risk.ActionStatus;
import com.agentgate.risk.DecisionBasis;
import com.agentgate.risk.RiskLevel;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Audit log filters, ordering, limits and stats, answered by the database. */
@SpringBootTest
@ActiveProfiles("test")
class AuditLogQueryIntegrationTest {

    @Autowired private AuditLogService service;
    @Autowired private AuditLogRepository repository;

    @BeforeEach
    void setUp() {
        cleanUp();
        repository.saveAll(List.of(
                log("mail-agent", "SEND_EMAIL", RiskLevel.HIGH, ActionStatus.APPROVAL_REQUIRED, "exec-1"),
                log("mail-agent", "DELETE_DATA", RiskLevel.BLOCKED, ActionStatus.BLOCKED, "exec-1"),
                log("mail-agent", "VIEW_DATA", RiskLevel.LOW, ActionStatus.ALLOWED, null),
                log("other-agent", "VIEW_DATA", RiskLevel.LOW, ActionStatus.ALLOWED, "exec-2")));
    }

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    private static AuditLog log(String agentId, String action, RiskLevel risk, ActionStatus status, String executionId) {
        return new AuditLog(agentId, action, null, List.of("PII"), risk, status, null, DecisionBasis.POLICY, null,
                executionId);
    }

    private static List<String> actions(List<AuditLogResponse> logs) {
        return logs.stream().map(AuditLogResponse::action).toList();
    }

    @Test
    void listsNewestFirst() {
        assertThat(actions(service.list(null, null, null, null, null)))
                .containsExactly("VIEW_DATA", "VIEW_DATA", "DELETE_DATA", "SEND_EMAIL");
    }

    @Test
    void filtersByExecution() {
        List<AuditLogResponse> logs = service.list(null, "exec-1", null, null, null);

        assertThat(actions(logs)).containsExactly("DELETE_DATA", "SEND_EMAIL");
        assertThat(logs).allSatisfy(l -> assertThat(l.executionId()).isEqualTo("exec-1"));
        assertThat(logs.getFirst().labels()).containsExactly("PII");
    }

    @Test
    void combinesFiltersAndLimits() {
        assertThat(actions(service.list("mail-agent", null, ActionStatus.APPROVAL_REQUIRED, RiskLevel.HIGH, null)))
                .containsExactly("SEND_EMAIL");
        assertThat(service.list(null, null, null, null, 2)).hasSize(2);
        assertThat(service.list(null, null, null, null, 0)).hasSize(1);
    }

    @Test
    void statsCountInTheDatabase() {
        AuditStatsResponse stats = service.stats("mail-agent");

        assertThat(stats.totalCount()).isEqualTo(3);
        assertThat(stats.countByRiskLevel()).containsEntry(RiskLevel.LOW, 1L).containsEntry(RiskLevel.BLOCKED, 1L);
        assertThat(stats.countByStatus()).containsEntry(ActionStatus.ALLOWED, 1L);
        assertThat(stats.blockRate()).isEqualTo(1.0 / 3);
        assertThat(service.stats("nobody").blockRate()).isZero();
    }
}
