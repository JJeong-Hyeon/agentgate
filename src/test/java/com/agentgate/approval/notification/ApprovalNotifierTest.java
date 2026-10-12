package com.agentgate.approval.notification;

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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
class ApprovalNotifierTest {

    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    @Mock
    private ApprovalRequestRepository repository;

    @Mock
    private ApprovalWebhook webhook;

    @Mock
    private TransactionTemplate transactionTemplate;

    private ApprovalNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new ApprovalNotifier(repository, webhook, transactionTemplate, Clock.fixed(NOW, ZoneOffset.UTC));
        when(webhook.isEnabled()).thenReturn(true);
        doAnswer(invocation -> {
            invocation.<Consumer<TransactionStatus>>getArgument(0).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private ApprovalRequest pending(Long id) {
        ApprovalRequest approval = new ApprovalRequest("mail-agent", "SEND_EMAIL", null, List.of(), RiskLevel.HIGH);
        ReflectionTestUtils.setField(approval, "id", id);
        when(repository.findById(id)).thenReturn(Optional.of(approval));
        return approval;
    }

    @Test
    void marksNotifiedWhenWebhookAccepts() {
        ApprovalRequest approval = pending(1L);
        when(webhook.send(approval)).thenReturn(true);

        notifier.onApprovalRequested(new ApprovalRequestedEvent(1L));

        assertThat(approval.needsApproverNotification()).isFalse();
    }

    @Test
    void leavesUnnotifiedWhenWebhookFails() {
        ApprovalRequest approval = pending(1L);
        when(webhook.send(approval)).thenReturn(false);

        notifier.notifyApprovers(1L);

        assertThat(approval.needsApproverNotification()).isTrue();
    }

    @Test
    void skipsRequestsAlreadyDecided() {
        ApprovalRequest approval = pending(1L);
        approval.approve("alice");

        notifier.notifyApprovers(1L);

        verify(webhook, never()).send(any());
    }

    @Test
    void doesNothingWhenDisabled() {
        when(webhook.isEnabled()).thenReturn(false);

        notifier.notifyApprovers(1L);
        notifier.retryUndelivered();

        verify(repository, never()).findById(any());
        verify(webhook, never()).send(any());
    }

    @Test
    void retriesPendingRequestsOlderThanTheGracePeriod() {
        ApprovalRequest approval = pending(3L);
        when(repository.findByStatusAndApproverNotifiedAtIsNullAndCreatedAtBefore(ApprovalStatus.PENDING,
                NOW.minusSeconds(30))).thenReturn(List.of(approval));
        when(webhook.send(approval)).thenReturn(true);

        notifier.retryUndelivered();

        assertThat(approval.needsApproverNotification()).isFalse();
    }
}
