package com.agentgate.observability;

import com.agentgate.approval.domain.ApprovalRequest;
import com.agentgate.approval.domain.ApprovalStatus;
import com.agentgate.approval.repository.ApprovalRequestRepository;
import com.agentgate.execution.domain.Execution;
import com.agentgate.risk.ActionStatus;
import com.agentgate.risk.DecisionBasis;
import com.agentgate.risk.RiskLevel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What AgentGate decides and what the agents it governs do, as Prometheus metrics. Tags only take values
 * from bounded sets (statuses, configured workflows, agents, models and tools), never ids of single runs.
 */
@Component
public class AgentGateMetrics {

    static final String UNKNOWN = "unknown";

    private static final Logger log = LoggerFactory.getLogger(AgentGateMetrics.class);

    private final MeterRegistry registry;
    private final ObjectMapper objectMapper;

    public AgentGateMetrics(MeterRegistry registry, ObjectMapper objectMapper,
                            ApprovalRequestRepository approvalRequestRepository) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        Gauge.builder("agentgate.approvals.pending", approvalRequestRepository,
                        repository -> repository.countByStatus(ApprovalStatus.PENDING))
                .description("Approval requests waiting for a decision")
                .register(registry);
    }

    public void decision(ActionStatus status, DecisionBasis basis, RiskLevel riskLevel) {
        Counter.builder("agentgate.governance.decisions")
                .description("Actions AgentGate evaluated, by outcome and the rule that decided")
                .tag("status", name(status))
                .tag("basis", name(basis))
                .tag("risk_level", name(riskLevel))
                .register(registry)
                .increment();
    }

    /** An approval request was approved, rejected or expired. */
    public void approvalDecided(ApprovalRequest approval) {
        Timer.builder("agentgate.approval.wait")
                .description("Time approval requests waited for a decision, by outcome")
                .tag("outcome", name(approval.getStatus()))
                .register(registry)
                .record(between(approval.getCreatedAt(), approval.getDecidedAt()));
    }

    /** An execution reached COMPLETED, STOPPED or FAILED. */
    public void executionFinished(Execution execution) {
        Timer.builder("agentgate.execution.duration")
                .description("Executions that finished, by workflow and outcome, with their run time")
                .tag("workflow", orUnknown(execution.getWorkflowId()))
                .tag("status", name(execution.getStatus()))
                .register(registry)
                .record(between(execution.getCreatedAt(), execution.getFinishedAt()));
    }

    /**
     * Model and tool activity from an agent step the runtime reported ({@code {"agent": {...}}} output):
     * the model call behind a turn, and how long a tool ran.
     */
    public void agentStep(String output) {
        JsonNode trace = agentTrace(output);
        if (trace == null) {
            return;
        }
        String agent = orUnknown(trace.path("agent").asString(null));
        JsonNode llm = trace.get("llm");
        if (llm != null && llm.isObject()) {
            String model = orUnknown(llm.path("model").asString(null));
            Timer.builder("agentgate.llm.latency")
                    .description("Model calls agents made, with how long the model took to answer")
                    .tag("model", model)
                    .tag("agent", agent)
                    .register(registry)
                    .record(Duration.ofMillis(Math.max(llm.path("latency_ms").asLong(0), 0)));
            JsonNode tokens = trace.get("tokens");
            if (tokens != null && tokens.isObject()) {
                tokens(model, agent, "input", tokens.path("input").asLong(0));
                tokens(model, agent, "output", tokens.path("output").asLong(0));
            }
        }
        if ("result".equals(trace.path("kind").asString(null)) && trace.hasNonNull("duration_ms")) {
            Timer.builder("agentgate.tool.duration")
                    .description("Tool runs by agents, by tool and outcome, with how long the tool ran")
                    .tag("tool", toolName(trace.path("tool").asString("")))
                    .tag("status", orUnknown(trace.path("status").asString(null)))
                    .tag("agent", agent)
                    .register(registry)
                    .record(Duration.ofMillis(Math.max(trace.path("duration_ms").asLong(0), 0)));
        }
    }

    private void tokens(String model, String agent, String direction, long count) {
        if (count <= 0) {
            return;
        }
        Counter.builder("agentgate.llm.tokens")
                .description("Tokens agents' model calls used, by direction")
                .tag("model", model)
                .tag("agent", agent)
                .tag("direction", direction)
                .register(registry)
                .increment(count);
    }

    private JsonNode agentTrace(String output) {
        if (output == null || !output.contains("\"agent\"")) {
            return null;
        }
        try {
            JsonNode trace = objectMapper.readTree(output).get("agent");
            return trace != null && trace.isObject() ? trace : null;
        } catch (JacksonException e) {
            // Outputs are clipped by the runtime; a cut-off one has nothing to count.
            log.debug("Agent step output is not JSON: {}", e.getMessage());
            return null;
        }
    }

    /** "notes/save_note" from "helper:notes/save_note"; the node prefix would make every node a new series. */
    static String toolName(String recorded) {
        String name = recorded.substring(recorded.indexOf(':') + 1);
        return name.isBlank() ? UNKNOWN : name;
    }

    private static Duration between(java.time.Instant start, java.time.Instant end) {
        if (start == null || end == null || end.isBefore(start)) {
            return Duration.ZERO;
        }
        return Duration.between(start, end);
    }

    private static String name(Enum<?> value) {
        return value == null ? UNKNOWN : value.name();
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }
}
