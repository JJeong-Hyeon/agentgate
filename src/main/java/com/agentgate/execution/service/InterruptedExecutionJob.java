package com.agentgate.execution.service;

import com.agentgate.execution.domain.Execution;
import com.agentgate.execution.domain.ExecutionStatus;
import com.agentgate.execution.dto.ExecutionEvent;
import com.agentgate.execution.repository.ExecutionRepository;
import com.agentgate.runtime.RuntimeClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Finds executions a runtime restart left behind: still RUNNING here, but not running on the runtime. The runtime
 * continues them from their last checkpoint; ones it cannot continue (nothing saved, or cut off during a tool call
 * that may already have taken effect) are marked FAILED instead of staying RUNNING forever.
 */
@Component
public class InterruptedExecutionJob {

    private static final Logger log = LoggerFactory.getLogger(InterruptedExecutionJob.class);
    static final String NOT_RECOVERABLE =
            "Interrupted by a runtime restart and could not be continued; a tool call in progress may or may not have run";

    private final ExecutionRepository executionRepository;
    private final ExecutionService executionService;
    private final RuntimeClient runtimeClient;
    private final ExecutionStreamBroadcaster broadcaster;
    private final Duration grace;
    private final Clock clock;

    @Autowired
    public InterruptedExecutionJob(ExecutionRepository executionRepository, ExecutionService executionService,
                                   RuntimeClient runtimeClient, ExecutionStreamBroadcaster broadcaster,
                                   @Value("${agentgate.runtime.recovery-grace:PT2M}") Duration grace) {
        this(executionRepository, executionService, runtimeClient, broadcaster, grace, Clock.systemUTC());
    }

    InterruptedExecutionJob(ExecutionRepository executionRepository, ExecutionService executionService,
                            RuntimeClient runtimeClient, ExecutionStreamBroadcaster broadcaster, Duration grace,
                            Clock clock) {
        this.executionRepository = executionRepository;
        this.executionService = executionService;
        this.runtimeClient = runtimeClient;
        this.broadcaster = broadcaster;
        this.grace = grace;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${agentgate.runtime.recovery-check-delay:PT1M}")
    public void recoverInterrupted() {
        // Just-started executions may not be running on the runtime yet.
        List<Execution> running = executionRepository.findByStatusAndCreatedAtBefore(
                ExecutionStatus.RUNNING, clock.instant().minus(grace));
        if (running.isEmpty()) {
            return;
        }
        Optional<Set<String>> active = runtimeClient.activeExecutions();
        if (active.isEmpty()) {
            return;
        }
        for (Execution execution : running) {
            String id = execution.getExecutionId();
            if (active.get().contains(id)) {
                continue;
            }
            switch (runtimeClient.recover(id)) {
                case RECOVERING -> log.info("Recovering execution {} interrupted by a runtime restart", id);
                case IMPOSSIBLE -> fail(id);
                case NOT_INTERRUPTED, UNAVAILABLE -> { }
            }
        }
    }

    // Like the runtime's own failure event, so open Execution Studio pages update too.
    private void fail(String executionId) {
        ExecutionEvent event = new ExecutionEvent(ExecutionEvent.Type.EXECUTION_FAILED, null, null, null, null,
                NOT_RECOVERABLE, null, clock.instant());
        broadcaster.publish(executionId, "update", Map.of("event", event,
                "execution", executionService.apply(executionId, event)));
        broadcaster.complete(executionId);
    }
}
