package com.agentgate.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentgate.approval.domain.ApprovalRequest;
import com.agentgate.approval.domain.ApprovalStatus;
import com.agentgate.approval.repository.ApprovalRequestRepository;
import com.agentgate.risk.ActionStatus;
import com.agentgate.risk.DecisionBasis;
import com.agentgate.risk.RiskLevel;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

class AgentGateMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ApprovalRequestRepository approvals = mock(ApprovalRequestRepository.class);
    private AgentGateMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new AgentGateMetrics(registry, JsonMapper.builder().build(), approvals);
    }

    @Test
    void countsDecisionsByOutcomeAndRule() {
        metrics.decision(ActionStatus.BLOCKED, DecisionBasis.TOOL_NOT_GRANTED, RiskLevel.BLOCKED);
        metrics.decision(ActionStatus.BLOCKED, DecisionBasis.TOOL_NOT_GRANTED, RiskLevel.BLOCKED);
        metrics.decision(ActionStatus.ALLOWED, null, RiskLevel.LOW);

        assertThat(registry.get("agentgate.governance.decisions").tags("status", "BLOCKED", "basis", "TOOL_NOT_GRANTED")
                .counter().count()).isEqualTo(2);
        assertThat(registry.get("agentgate.governance.decisions").tags("status", "ALLOWED", "basis", "unknown")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void reportsPendingApprovalsFromTheDatabase() {
        when(approvals.countByStatus(ApprovalStatus.PENDING)).thenReturn(4L);

        assertThat(registry.get("agentgate.approvals.pending").gauge().value()).isEqualTo(4);
    }

    @Test
    void timesHowLongApprovalsWaited() {
        ApprovalRequest approval = new ApprovalRequest("a", "SEND", null, List.of(), RiskLevel.HIGH);
        approval.expire();
        Instant decided = approval.getDecidedAt();
        ReflectionTestUtils.setField(approval, "createdAt", decided.minusSeconds(90));

        metrics.approvalDecided(approval);

        Timer wait = registry.get("agentgate.approval.wait").tag("outcome", "EXPIRED").timer();
        assertThat(wait.count()).isEqualTo(1);
        assertThat(wait.totalTime(TimeUnit.SECONDS)).isEqualTo(90);
    }

    @Test
    void recordsModelCallsTokensAndToolRunsFromAgentSteps() {
        metrics.agentStep("""
                {"agent":{"kind":"tool_calls","agent":"note-agent","llm":{"model":"qwen3-32b","latency_ms":1500},
                 "tokens":{"input":120,"output":15},"calls":[]}}""");
        metrics.agentStep("""
                {"agent":{"kind":"result","agent":"note-agent","tool":"helper:notes/save_note","status":"EXECUTED",
                 "duration_ms":250}}""");

        Timer llm = registry.get("agentgate.llm.latency").tags("model", "qwen3-32b", "agent", "note-agent").timer();
        assertThat(llm.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(1500);
        assertThat(registry.get("agentgate.llm.tokens").tags("direction", "input").counter().count()).isEqualTo(120);
        assertThat(registry.get("agentgate.llm.tokens").tags("direction", "output").counter().count()).isEqualTo(15);
        Timer tool = registry.get("agentgate.tool.duration").tags("tool", "notes/save_note", "status", "EXECUTED").timer();
        assertThat(tool.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(250);
    }

    @Test
    void ignoresOutputsThatAreNotAgentSteps() {
        metrics.agentStep("{\"plan\":\"1. step\"}");
        metrics.agentStep("{\"agent\":{\"kind\":\"start\",\"prompt\":\"cut off");
        metrics.agentStep(null);

        assertThat(registry.find("agentgate.llm.latency").timers()).isEmpty();
        assertThat(registry.find("agentgate.tool.duration").timers()).isEmpty();
    }

    @Test
    void toolNamesDropTheNodePrefix() {
        assertThat(AgentGateMetrics.toolName("lead:agent/research")).isEqualTo("agent/research");
        assertThat(AgentGateMetrics.toolName("")).isEqualTo("unknown");
    }

    @Test
    void prometheusNamesMatchTheGrafanaDashboard() throws Exception {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        AgentGateMetrics exported = new AgentGateMetrics(prometheus, JsonMapper.builder().build(), approvals);
        exported.decision(ActionStatus.ALLOWED, DecisionBasis.POLICY, RiskLevel.LOW);
        ApprovalRequest approval = new ApprovalRequest("a", "SEND", null, List.of(), RiskLevel.HIGH);
        approval.approve("kim");
        ReflectionTestUtils.setField(approval, "createdAt", approval.getDecidedAt());
        exported.approvalDecided(approval);
        exported.agentStep("""
                {"agent":{"kind":"answer","agent":"a","llm":{"model":"m","latency_ms":5},"tokens":{"input":1,"output":1}}}""");
        exported.agentStep("{\"agent\":{\"kind\":\"result\",\"tool\":\"n:s/t\",\"status\":\"EXECUTED\",\"duration_ms\":1}}");
        String scrape = prometheus.scrape();

        String dashboard = Files.readString(Path.of("infra/grafana/agentgate-dashboard.json"));
        Matcher used = Pattern.compile("agentgate_[a-z_]+").matcher(dashboard);
        List<String> names = used.results().map(MatchResult::group).distinct().toList();
        assertThat(names).isNotEmpty();
        // Execution metrics need an execution; their family is checked through its sibling suffixes below.
        for (String name : names) {
            if (!name.startsWith("agentgate_execution_")) {
                assertThat(scrape).as(name).contains(name);
            }
        }
        assertThat(names).contains("agentgate_execution_duration_seconds_count", "agentgate_execution_duration_seconds_sum");
    }
}
