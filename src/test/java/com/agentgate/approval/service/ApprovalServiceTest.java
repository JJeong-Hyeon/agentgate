package com.agentgate.approval.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.agentgate.approval.domain.ApprovalRequest;
import com.agentgate.approval.domain.ApprovalStatus;
import com.agentgate.approval.dto.ApprovalResponse;
import com.agentgate.approval.repository.ApprovalRequestRepository;
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

@ExtendWith(MockitoExtension.class)
class ApprovalServiceTest {

    @Mock
    private ApprovalRequestRepository approvalRequestRepository;

    private ApprovalService service;

    @BeforeEach
    void setUp() {
        service = new ApprovalService(approvalRequestRepository);
    }

    @Test
    void createsApprovalRequest() {
        when(approvalRequestRepository.save(org.mockito.ArgumentMatchers.any(ApprovalRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApprovalRequest result = service.createRequest("mail-agent", "SEND_EMAIL", null, List.of("PII"), RiskLevel.HIGH);

        assertThat(result.getAgentId()).isEqualTo("mail-agent");
        assertThat(result.getStatus()).isEqualTo(ApprovalStatus.PENDING);
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
    }

    @Test
    void approveSucceedsForPendingRequest() {
        ApprovalRequest existing = withId(new ApprovalRequest("mail-agent", "SEND_EMAIL", null, List.of(), RiskLevel.HIGH), 1L);
        when(approvalRequestRepository.findById(1L)).thenReturn(Optional.of(existing));

        ApprovalResponse response = service.approve(1L, "alice");

        assertThat(response.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(response.decidedBy()).isEqualTo("alice");
    }

    @Test
    void listFiltersByStatusWhenProvided() {
        when(approvalRequestRepository.findByStatus(ApprovalStatus.PENDING))
                .thenReturn(List.of(withId(new ApprovalRequest("mail-agent", "SEND_EMAIL", null, List.of(), RiskLevel.HIGH), 1L)));

        List<ApprovalResponse> result = service.list(ApprovalStatus.PENDING);

        assertThat(result).hasSize(1);
    }

    @Test
    void listReturnsAllWhenNoFilter() {
        when(approvalRequestRepository.findAll())
                .thenReturn(List.of(withId(new ApprovalRequest("mail-agent", "SEND_EMAIL", null, List.of(), RiskLevel.HIGH), 1L)));

        List<ApprovalResponse> result = service.list(null);

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
