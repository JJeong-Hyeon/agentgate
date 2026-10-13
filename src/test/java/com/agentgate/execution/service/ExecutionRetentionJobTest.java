package com.agentgate.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentgate.execution.domain.Execution;
import com.agentgate.execution.domain.NodeExecution;
import com.agentgate.execution.repository.ExecutionRepository;
import com.agentgate.execution.repository.NodeExecutionRepository;
import com.agentgate.runtime.RuntimeClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest
@ActiveProfiles("test")
class ExecutionRetentionJobTest {

    private static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");

    @Autowired private ExecutionRepository executionRepository;
    @Autowired private NodeExecutionRepository nodeExecutionRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean
    private RuntimeClient runtimeClient;

    private ExecutionRetentionJob job;

    @BeforeEach
    void setUp() {
        cleanUp();
        when(runtimeClient.forgetExecution(anyString())).thenReturn(true);
        job = job(Duration.ofDays(90));
    }

    @AfterEach
    void cleanUp() {
        nodeExecutionRepository.deleteAll();
        executionRepository.deleteAll();
    }

    @Test
    void executionsThatEndedBeforeTheRetentionPeriodAreDeletedWithTheirNodes() {
        Execution old = ended("old", Duration.ofDays(100));
        nodeExecutionRepository.save(new NodeExecution(old, "t1", "a", "a"));
        ended("recent", Duration.ofDays(10));
        executionRepository.save(new Execution("running", "wf", 1, "t"));

        job.purgeExpired();

        assertThat(executionRepository.findAll()).extracting(Execution::getExecutionId)
                .containsExactlyInAnyOrder("recent", "running");
        assertThat(nodeExecutionRepository.findAll()).isEmpty();
        verify(runtimeClient).forgetExecution("old");
        verify(runtimeClient, never()).forgetExecution("recent");
    }

    @Test
    void anExecutionTheRuntimeCouldNotForgetIsKeptForTheNextRun() {
        ended("old", Duration.ofDays(100));
        when(runtimeClient.forgetExecution("old")).thenReturn(false);

        job.purgeExpired();

        assertThat(executionRepository.findByExecutionId("old")).isPresent();
    }

    @Test
    void zeroRetentionKeepsEverything() {
        ended("old", Duration.ofDays(1000));

        job(Duration.ZERO).purgeExpired();

        assertThat(executionRepository.findByExecutionId("old")).isPresent();
    }

    private Execution ended(String executionId, Duration ago) {
        Execution execution = new Execution(executionId, "wf", 1, "t");
        execution.completed(NOW.minus(ago));
        return executionRepository.save(execution);
    }

    private ExecutionRetentionJob job(Duration retention) {
        return new ExecutionRetentionJob(executionRepository, nodeExecutionRepository, runtimeClient,
                transactionManager, retention, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
