package com.agentgate.execution.service;

import com.agentgate.execution.domain.Execution;
import com.agentgate.execution.domain.ExecutionStatus;
import com.agentgate.execution.repository.ExecutionRepository;
import com.agentgate.execution.repository.NodeExecutionRepository;
import com.agentgate.runtime.RuntimeClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes executions that ended longer ago than the retention period: first their checkpoints on the runtime,
 * then their records here. Audit logs and approval requests are governance records and are kept.
 */
@Component
public class ExecutionRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(ExecutionRetentionJob.class);
    private static final Set<ExecutionStatus> ENDED =
            EnumSet.of(ExecutionStatus.COMPLETED, ExecutionStatus.STOPPED, ExecutionStatus.FAILED);
    private static final int BATCH = 100;

    private final ExecutionRepository executionRepository;
    private final NodeExecutionRepository nodeExecutionRepository;
    private final RuntimeClient runtimeClient;
    private final TransactionTemplate transaction;
    private final Duration retention;
    private final Clock clock;

    @Autowired
    public ExecutionRetentionJob(ExecutionRepository executionRepository,
                                 NodeExecutionRepository nodeExecutionRepository, RuntimeClient runtimeClient,
                                 PlatformTransactionManager transactionManager,
                                 @Value("${agentgate.retention.executions:P90D}") Duration retention) {
        this(executionRepository, nodeExecutionRepository, runtimeClient, transactionManager, retention,
                Clock.systemUTC());
    }

    ExecutionRetentionJob(ExecutionRepository executionRepository, NodeExecutionRepository nodeExecutionRepository,
                          RuntimeClient runtimeClient, PlatformTransactionManager transactionManager,
                          Duration retention, Clock clock) {
        if (retention.isNegative()) {
            throw new IllegalArgumentException("agentgate.retention.executions must not be negative");
        }
        this.executionRepository = executionRepository;
        this.nodeExecutionRepository = nodeExecutionRepository;
        this.runtimeClient = runtimeClient;
        this.transaction = new TransactionTemplate(transactionManager);
        this.retention = retention;
        this.clock = clock;
    }

    /** P0D keeps everything. */
    @Scheduled(fixedDelayString = "${agentgate.retention.check-delay:PT1H}")
    public void purgeExpired() {
        if (retention.isZero()) {
            return;
        }
        Instant cutoff = clock.instant().minus(retention);
        int purged = 0;
        while (true) {
            List<Execution> batch = executionRepository.findEndedBefore(ENDED, cutoff, PageRequest.of(0, BATCH));
            for (Execution execution : batch) {
                // The runtime first: if it cannot be reached, keep the record so the next run tries again.
                if (!runtimeClient.forgetExecution(execution.getExecutionId())) {
                    log(purged);
                    return;
                }
                transaction.executeWithoutResult(status -> {
                    nodeExecutionRepository.deleteByExecution(execution);
                    executionRepository.deleteById(execution.getId());
                });
                purged++;
            }
            if (batch.size() < BATCH) {
                log(purged);
                return;
            }
        }
    }

    private void log(int purged) {
        if (purged > 0) {
            log.info("Deleted {} execution(s) that ended more than {} ago", purged, retention);
        }
    }
}
