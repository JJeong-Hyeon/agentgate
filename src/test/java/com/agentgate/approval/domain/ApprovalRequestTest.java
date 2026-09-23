package com.agentgate.approval.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentgate.common.exception.IllegalApprovalStateException;
import com.agentgate.risk.RiskLevel;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApprovalRequestTest {

    @Test
    void approveTransitionsFromPendingToApproved() {
        ApprovalRequest request = new ApprovalRequest("mail-agent", "SEND_EMAIL", null, List.of("PII"), RiskLevel.HIGH);

        request.approve("alice");

        assertThat(request.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(request.getDecidedBy()).isEqualTo("alice");
        assertThat(request.getDecidedAt()).isNotNull();
    }

    @Test
    void rejectTransitionsFromPendingToRejected() {
        ApprovalRequest request = new ApprovalRequest("mail-agent", "SEND_EMAIL", null, List.of("PII"), RiskLevel.HIGH);

        request.reject("bob");

        assertThat(request.getStatus()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(request.getDecidedBy()).isEqualTo("bob");
    }

    @Test
    void approvingAlreadyDecidedRequestThrows() {
        ApprovalRequest request = new ApprovalRequest("mail-agent", "SEND_EMAIL", null, List.of("PII"), RiskLevel.HIGH);
        request.approve("alice");

        assertThatThrownBy(() -> request.approve("bob"))
                .isInstanceOf(IllegalApprovalStateException.class);
    }

    @Test
    void rejectingAlreadyDecidedRequestThrows() {
        ApprovalRequest request = new ApprovalRequest("mail-agent", "SEND_EMAIL", null, List.of("PII"), RiskLevel.HIGH);
        request.reject("bob");

        assertThatThrownBy(() -> request.reject("bob"))
                .isInstanceOf(IllegalApprovalStateException.class);
    }
}
