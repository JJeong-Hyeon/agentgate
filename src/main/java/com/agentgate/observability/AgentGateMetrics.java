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
import org.springframework.stereotype.Component;

/**
 * What AgentGate decides and what the agents it governs do, as Prometheus metrics. Tags only take values
 * from bounded sets (statuses, configured workflows, agents, models and tools), never ids of single runs.
 */
@Component
public class AgentGateMetrics {

    static final String UNKNOWN = "unknown";

    private final MeterRegistry registry;

    public AgentGateMetrics(MeterRegistry registry, ApprovalRequestRepository approvalRequestRepository) {
        this.registry = registry;
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

    /** Model and tool activity of an agent step the runtime reported. */
    public void agentStep(AgentStep step) {
        String agent = orUnknown(step.agent());
        if (step.hasLlmCall()) {
            String model = orUnknown(step.llmModel());
            Timer.builder("agentgate.llm.latency")
                    .description("Model calls agents made, with how long the model took to answer "
                            + "(the call itself, not time spent waiting under LLM_MAX_CONCURRENCY)")
                    .tag("model", model)
                    .tag("agent", agent)
                    .register(registry)
                    .record(Duration.ofMillis(step.llmLatencyMs()));
            if (step.llmQueueWaitMs() != null) {
                Timer.builder("agentgate.llm.queue_wait")
                        .description("Time model calls spent waiting for a free slot under "
                                + "LLM_MAX_CONCURRENCY before the call itself started")
                        .tag("model", model)
                        .tag("agent", agent)
                        .register(registry)
                        .record(Duration.ofMillis(step.llmQueueWaitMs()));
            }
            tokens(model, agent, "input", step.inputTokens());
            tokens(model, agent, "output", step.outputTokens());
        }
        if (step.hasToolRun()) {
            Timer.builder("agentgate.tool.duration")
                    .description("Tool runs by agents, by tool and outcome, with how long the tool ran")
                    .tag("tool", orUnknown(step.toolName()))
                    .tag("status", orUnknown(step.toolStatus()))
                    .tag("agent", agent)
                    .register(registry)
                    .record(Duration.ofMillis(step.toolDurationMs()));
        }
    }

    private void tokens(String model, String agent, String direction, Long count) {
        if (count == null || count <= 0) {
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
