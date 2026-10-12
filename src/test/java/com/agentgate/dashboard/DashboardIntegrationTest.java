package com.agentgate.dashboard;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentgate.approval.domain.ApprovalRequest;
import com.agentgate.approval.repository.ApprovalRequestRepository;
import com.agentgate.audit.domain.AuditLog;
import com.agentgate.audit.repository.AuditLogRepository;
import com.agentgate.execution.domain.Execution;
import com.agentgate.execution.domain.NodeExecution;
import com.agentgate.execution.repository.ExecutionRepository;
import com.agentgate.execution.repository.NodeExecutionRepository;
import com.agentgate.observability.AgentStep;
import com.agentgate.risk.ActionStatus;
import com.agentgate.risk.DecisionBasis;
import com.agentgate.risk.RiskLevel;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/** The dashboard's figures, computed by the database from executions, steps, approvals and decisions. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ExecutionRepository executionRepository;
    @Autowired private NodeExecutionRepository nodeExecutionRepository;
    @Autowired private ApprovalRequestRepository approvalRepository;
    @Autowired private AuditLogRepository auditLogRepository;

    private final Instant now = Instant.now();

    @BeforeEach
    void setUp() {
        cleanUp();
        finished("e-ok", "COMPLETED", 2, 30);
        finished("e-stop", "STOPPED", 3, 10);
        finished("e-old", "FAILED", 30, 5);   // 30 hours ago: outside 24h, inside 7d
        Execution waiting = executionRepository.save(new Execution("e-wait", "assistant", 1, "t"));
        waiting.waitingForApproval(1L);
        executionRepository.save(waiting);

        Execution ok = executionRepository.findByExecutionId("e-ok").orElseThrow();
        step(ok, "t1", "{\"agent\":{\"kind\":\"answer\",\"agent\":\"a\",\"llm\":{\"model\":\"qwen3\",\"latency_ms\":1000},"
                + "\"tokens\":{\"input\":100,\"output\":20}}}");
        step(ok, "t2", "{\"agent\":{\"kind\":\"answer\",\"agent\":\"a\",\"llm\":{\"model\":\"qwen3\",\"latency_ms\":3000}}}");
        step(ok, "t3", "{\"answer\":\"plain node\"}");

        approvalRepository.save(
                new ApprovalRequest("a", "MEMORY:SAVE", null, List.of(), RiskLevel.MEDIUM, "e-wait"));
        ApprovalRequest approved = approvalRepository.save(
                new ApprovalRequest("a", "SEND_EMAIL", null, List.of(), RiskLevel.HIGH, "e-ok"));
        approved.approve("kim");
        approvalRepository.save(approved);
        backdate("approval_requests", "created_at", approved.getId(), 3, 0);
        jdbc.update("update approval_requests set decided_at = ? where id = ?",
                Timestamp.from(now.minus(2, ChronoUnit.HOURS).minus(30, ChronoUnit.MINUTES)), approved.getId());

        audit("MCP:files:delete", ActionStatus.BLOCKED);
        audit("MCP:files:delete", ActionStatus.BLOCKED);
        audit("MCP:mail:send", ActionStatus.BLOCKED);
        audit("MEMORY:SEARCH", ActionStatus.ALLOWED);
        audit("MEMORY:SAVE", ActionStatus.APPROVAL_REQUIRED);
    }

    @AfterEach
    void cleanUp() {
        nodeExecutionRepository.deleteAll();
        executionRepository.deleteAll();
        approvalRepository.deleteAll();
        auditLogRepository.deleteAll();
    }

    private void finished(String id, String status, int hoursAgo, int runMinutes) {
        Execution execution = executionRepository.save(new Execution(id, "assistant", 1, "t"));
        Instant end = now.minus(hoursAgo, ChronoUnit.HOURS).plus(runMinutes, ChronoUnit.MINUTES);
        switch (status) {
            case "COMPLETED" -> execution.completed(end);
            case "STOPPED" -> execution.stopped("rejected by an approver", end);
            default -> execution.failed("model timed out", end);
        }
        executionRepository.save(execution);
        jdbc.update("update executions set created_at = ? where execution_id = ?",
                Timestamp.from(now.minus(hoursAgo, ChronoUnit.HOURS)), id);
    }

    private void step(Execution execution, String taskId, String output) {
        NodeExecution node = new NodeExecution(execution, taskId, "assistant", "assistant.think");
        node.completed(output, now.minus(2, ChronoUnit.HOURS));
        node.recordCost(AgentStep.parse(output, objectMapper));
        nodeExecutionRepository.save(node);
    }

    private void audit(String action, ActionStatus status) {
        auditLogRepository.save(new AuditLog("a", action, null, List.of(), RiskLevel.LOW, status, null,
                DecisionBasis.POLICY, null, null));
    }

    private void backdate(String table, String column, Long id, int hoursAgo, int minutes) {
        jdbc.update("update " + table + " set " + column + " = ? where id = ?",
                Timestamp.from(now.minus(hoursAgo, ChronoUnit.HOURS).plus(minutes, ChronoUnit.MINUTES)), id);
    }

    private ResultActions dashboard(String period) throws Exception {
        return mockMvc.perform(get("/api/v1/dashboard").param("period", period)
                .with(httpBasic("test-admin", "test-password")));
    }

    @Test
    void summarisesTheLastDay() throws Exception {
        dashboard("24h")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("24h"))
                .andExpect(jsonPath("$.executions.total").value(3))
                .andExpect(jsonPath("$.executions.byStatus.COMPLETED").value(1))
                .andExpect(jsonPath("$.executions.byStatus.STOPPED").value(1))
                .andExpect(jsonPath("$.executions.byStatus.FAILED").value(0))
                .andExpect(jsonPath("$.executions.waitingApproval").value(1))
                .andExpect(jsonPath("$.executions.avgDurationMs").value(20 * 60_000))
                .andExpect(jsonPath("$.executions.buckets.length()").value(24))
                .andExpect(jsonPath("$.approvals.pending").value(1))
                .andExpect(jsonPath("$.approvals.oldestPending[0].action").value("MEMORY:SAVE"))
                .andExpect(jsonPath("$.approvals.decided.APPROVED").value(1))
                .andExpect(jsonPath("$.approvals.avgWaitMs").value(30 * 60_000))
                .andExpect(jsonPath("$.governance.total").value(5))
                .andExpect(jsonPath("$.governance.blocked").value(3))
                .andExpect(jsonPath("$.governance.blockRate").value(0.6))
                .andExpect(jsonPath("$.governance.topBlocked[0].action").value("MCP:files:delete"))
                .andExpect(jsonPath("$.governance.topBlocked[0].count").value(2))
                .andExpect(jsonPath("$.models[0].model").value("qwen3"))
                .andExpect(jsonPath("$.models[0].calls").value(2))
                .andExpect(jsonPath("$.models[0].avgLatencyMs").value(2000.0))
                .andExpect(jsonPath("$.models[0].inputTokens").value(100))
                .andExpect(jsonPath("$.recentProblems.length()").value(1))
                .andExpect(jsonPath("$.recentProblems[0].status").value("STOPPED"));
    }

    @Test
    void theWeekIncludesOlderRunsInDailyBuckets() throws Exception {
        dashboard("7d")
                .andExpect(jsonPath("$.executions.total").value(4))
                .andExpect(jsonPath("$.executions.byStatus.FAILED").value(1))
                .andExpect(jsonPath("$.executions.buckets.length()").value(7))
                .andExpect(jsonPath("$.recentProblems.length()").value(2));
    }

    @Test
    void rejectsUnknownPeriods() throws Exception {
        dashboard("1y").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PERIOD"));
    }

    @Test
    void needsSignIn() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard")).andExpect(status().isUnauthorized());
    }
}
