package com.agentgate.approval.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agentgate.approval.domain.ApprovalRequest;
import com.agentgate.approval.domain.ApprovalStatus;
import com.agentgate.approval.dto.ApprovalResponse;
import com.agentgate.approval.repository.ApprovalRequestRepository;
import com.agentgate.approval.runtime.ApprovalDecidedEvent;
import com.agentgate.common.exception.ApprovalNotFoundException;
import com.agentgate.common.exception.IllegalApprovalStateException;
import com.agentgate.risk.RiskLevel;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class ApprovalServiceTest {

    @Mock
    private ApprovalRequestRepository approvalRequestRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ApprovalService service;

    @BeforeEach
    void setUp() {
        service = new ApprovalService(approvalRequestRepository, eventPublisher);
    }

    @Test
    void createsApprovalRequest() {
        when(approvalRequestRepository.save(org.mockito.ArgumentMatchers.any(ApprovalRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApprovalRequest result = service.createRequest("mail-agent", "SEND_EMAIL", null, List.of("PII"), RiskLevel.HIGH, "exec-1", null, null);

        assertThat(result.getAgentId()).isEqualTo("mail-agent");
        assertThat(result.getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(result.getExecutionId()).isEqualTo("exec-1");
    }

    @Test
    void approveThrowsWhenNotFound() {
        when(approvalRequestRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(99L, "alice")).isInstanceOf(ApprovalNotFoundException.class);
    }

    @Test
    void approveThrowsWhenAlreadyDecided() {
        ApprovalRequest existing = withId(new ApprovalRequest("mail-agent", "SEND_EMAIL", null, List.of(), RiskLevel.HIGH), 1L);
        existing.approve("alice");
        when(approvalRequestRepository.findById(1L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.approve(1L, "bob")).isInstanceOf(IllegalApprovalStateException.class);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void approveSucceedsForPendingRequest() {
        ApprovalRequest existing = withId(new ApprovalRequest("mail-agent", "SEND_EMAIL", null, List.of(), RiskLevel.HIGH), 1L);
        when(approvalRequestRepository.findById(1L)).thenReturn(Optional.of(existing));

        ApprovalResponse response = service.approve(1L, "alice");

        assertThat(response.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(response.decidedBy()).isEqualTo("alice");
        verify(eventPublisher).publishEvent(new ApprovalDecidedEvent(1L));
    }

    @Test
    void listFiltersByStatusWhenProvided() {
        when(approvalRequestRepository.findByStatus(ApprovalStatus.PENDING))
                .thenReturn(List.of(withId(new ApprovalRequest("mail-agent", "SEND_EMAIL", null, List.of(), RiskLevel.HIGH), 1L)));

        List<ApprovalResponse> result = service.list(ApprovalStatus.PENDING, null);

        assertThat(result).hasSize(1);
    }

    @Test
    void listReturnsAllWhenNoFilter() {
        when(approvalRequestRepository.findAll())
                .thenReturn(List.of(withId(new ApprovalRequest("mail-agent", "SEND_EMAIL", null, List.of(), RiskLevel.HIGH), 1L)));

        List<ApprovalResponse> result = service.list(null, null);

        assertThat(result).hasSize(1);
    }

    private static ApprovalRequest withId(ApprovalRequest approvalRequest, Long id) {
        try {
            Field field = ApprovalRequest.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(approvalRequest, id);
            return approvalRequest;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
