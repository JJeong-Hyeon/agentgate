package com.agentgate.approval.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentgate.approval.domain.ApprovalRequest;
import com.agentgate.approval.domain.ApprovalStatus;
import com.agentgate.approval.repository.ApprovalRequestRepository;
import com.agentgate.risk.RiskLevel;
import com.agentgate.runtime.RuntimeClient;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RuntimeResumeNotifierTest {

    @Mock
    private ApprovalRequestRepository repository;

    @Mock
    private RuntimeClient runtimeClient;

    @Mock
    private TransactionTemplate transactionTemplate;

    private RuntimeResumeNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new RuntimeResumeNotifier(repository, runtimeClient, transactionTemplate);
        when(runtimeClient.isEnabled()).thenReturn(true);
        // Run the transaction callback inline.
        doAnswer(invocation -> {
            invocation.<Consumer<TransactionStatus>>getArgument(0).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private ApprovalRequest approval(String executionId, boolean approved) {
        ApprovalRequest approval = new ApprovalRequest("runtime-agent", "SEND_REPORT", null, List.of(), RiskLevel.HIGH,
                executionId);
        ReflectionTestUtils.setField(approval, "id", 7L);
        if (approved) {
            approval.approve("alice");
        }
        when(repository.findById(7L)).thenReturn(Optional.of(approval));
        return approval;
    }

    @Test
    void deliveredDecisionIsMarkedNotified() {
        ApprovalRequest approval = approval("exec-1", true);
        when(runtimeClient.resume("exec-1", 7L, ApprovalStatus.APPROVED)).thenReturn(true);

        notifier.onApprovalDecided(new ApprovalDecidedEvent(7L));

        assertThat(approval.getRuntimeNotifiedAt()).isNotNull();
        assertThat(approval.needsRuntimeNotification()).isFalse();
    }

    @Test
    void failedDeliveryStaysPendingForRetry() {
        ApprovalRequest approval = approval("exec-1", true);
        when(runtimeClient.resume("exec-1", 7L, ApprovalStatus.APPROVED)).thenReturn(false);

        notifier.onApprovalDecided(new ApprovalDecidedEvent(7L));

        assertThat(approval.needsRuntimeNotification()).isTrue();
    }

    @Test
    void approvalWithoutExecutionIsNotSent() {
        approval(null, true);

        notifier.onApprovalDecided(new ApprovalDecidedEvent(7L));

        verify(runtimeClient, never()).resume(any(), any(), any());
    }

    @Test
    void pendingApprovalIsNotSent() {
        approval("exec-1", false);

        notifier.onApprovalDecided(new ApprovalDecidedEvent(7L));

        verify(runtimeClient, never()).resume(any(), any(), any());
    }

    @Test
    void disabledRuntimeIsNotCalled() {
        approval("exec-1", true);
        when(runtimeClient.isEnabled()).thenReturn(false);

        notifier.onApprovalDecided(new ApprovalDecidedEvent(7L));
        notifier.retryUndelivered();

        verify(runtimeClient, never()).resume(any(), any(), any());
    }

    @Test
    void retryResendsUndeliveredDecisions() {
        ApprovalRequest approval = approval("exec-1", true);
        when(repository.findByExecutionIdIsNotNullAndStatusNotAndRuntimeNotifiedAtIsNull(ApprovalStatus.PENDING))
                .thenReturn(List.of(approval));
        when(runtimeClient.resume("exec-1", 7L, ApprovalStatus.APPROVED)).thenReturn(true);

        notifier.retryUndelivered();

        assertThat(approval.needsRuntimeNotification()).isFalse();
    }
}
