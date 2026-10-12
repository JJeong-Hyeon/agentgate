package com.agentgate.approval.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agentgate.approval.domain.ApprovalRequest;
import com.agentgate.approval.domain.ApprovalStatus;
import com.agentgate.approval.repository.ApprovalRequestRepository;
import com.agentgate.approval.runtime.ApprovalDecidedEvent;
import com.agentgate.risk.RiskLevel;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class ApprovalExpiryJobTest {

    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    @Mock
    private ApprovalRequestRepository approvalRequestRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ApprovalExpiryJob job;

    @BeforeEach
    void setUp() {
        job = new ApprovalExpiryJob(approvalRequestRepository, eventPublisher, Duration.ofHours(24),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void expiresRequestsPendingLongerThanTtlAndNotifies() throws Exception {
        ApprovalRequest overdue = withId(new ApprovalRequest("mail-agent", "SEND_EMAIL", null, List.of(), RiskLevel.HIGH, "exec-1"), 7L);
        when(approvalRequestRepository.findByStatusAndCreatedAtBefore(ApprovalStatus.PENDING, NOW.minus(Duration.ofHours(24))))
                .thenReturn(List.of(overdue));

        job.expireOverdue();

        assertThat(overdue.getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(overdue.getDecidedBy()).isEqualTo(ApprovalRequest.SYSTEM);
        assertThat(overdue.needsRuntimeNotification()).isTrue();
        verify(eventPublisher).publishEvent(new ApprovalDecidedEvent(7L));
    }

    @Test
    void doesNothingWhenNothingIsOverdue() {
        when(approvalRequestRepository.findByStatusAndCreatedAtBefore(ApprovalStatus.PENDING, NOW.minus(Duration.ofHours(24))))
                .thenReturn(List.of());

        job.expireOverdue();

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void rejectsNonPositiveTtl() {
        assertThatThrownBy(() -> new ApprovalExpiryJob(approvalRequestRepository, eventPublisher, Duration.ZERO,
                Clock.systemUTC())).isInstanceOf(IllegalArgumentException.class);
    }

    private static ApprovalRequest withId(ApprovalRequest request, Long id) throws Exception {
        Field field = ApprovalRequest.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(request, id);
        return request;
    }
}
