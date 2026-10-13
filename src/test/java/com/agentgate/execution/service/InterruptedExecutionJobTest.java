package com.agentgate.execution.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentgate.execution.domain.Execution;
import com.agentgate.execution.domain.ExecutionStatus;
import com.agentgate.execution.dto.ExecutionEvent;
import com.agentgate.execution.dto.ExecutionResponse;
import com.agentgate.execution.repository.ExecutionRepository;
import com.agentgate.runtime.RuntimeClient;
import com.agentgate.runtime.RuntimeClient.Recovery;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InterruptedExecutionJobTest {

    private static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");

    private final ExecutionRepository executions = mock(ExecutionRepository.class);
    private final ExecutionService executionService = mock(ExecutionService.class);
    private final RuntimeClient runtime = mock(RuntimeClient.class);
    private final ExecutionStreamBroadcaster broadcaster = mock(ExecutionStreamBroadcaster.class);
    private final InterruptedExecutionJob job = new InterruptedExecutionJob(executions, executionService, runtime,
            broadcaster, Duration.ofMinutes(2), Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(executions.findByStatusAndCreatedAtBefore(ExecutionStatus.RUNNING, NOW.minus(Duration.ofMinutes(2))))
                .thenReturn(List.of(new Execution("busy", "wf", 1, "t"), new Execution("lost", "wf", 1, "t"),
                        new Execution("cut", "wf", 1, "t")));
        when(runtime.activeExecutions()).thenReturn(Optional.of(Set.of("busy")));
        when(runtime.recover("lost")).thenReturn(Recovery.RECOVERING);
        when(runtime.recover("cut")).thenReturn(Recovery.IMPOSSIBLE);
        when(executionService.apply(any(), any())).thenReturn(mock(ExecutionResponse.class));
    }

    @Test
    void executionsTheRuntimeLostAreRecoveredOrMarkedFailed() {
        job.recoverInterrupted();

        verify(runtime, never()).recover("busy");
        verify(runtime).recover("lost");
        verify(executionService, never()).apply(eq("lost"), any());
        verify(executionService).apply(eq("cut"), argThat(e -> e.type() == ExecutionEvent.Type.EXECUTION_FAILED
                && InterruptedExecutionJob.NOT_RECOVERABLE.equals(e.error())));
        verify(broadcaster).complete("cut");
    }

    @Test
    void nothingIsDecidedWhenTheRuntimeCannotSayWhatItRuns() {
        when(runtime.activeExecutions()).thenReturn(Optional.empty());

        job.recoverInterrupted();

        verify(runtime, never()).recover(any());
        verify(executionService, never()).apply(any(), any());
    }
}
