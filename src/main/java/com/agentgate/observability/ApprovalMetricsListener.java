package com.agentgate.observability;

import com.agentgate.approval.repository.ApprovalRequestRepository;
import com.agentgate.approval.runtime.ApprovalDecidedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Counts approval decisions (by approvers or expiry) once they are committed. */
@Component
public class ApprovalMetricsListener {

    private final ApprovalRequestRepository approvalRequestRepository;
    private final AgentGateMetrics metrics;

    public ApprovalMetricsListener(ApprovalRequestRepository approvalRequestRepository, AgentGateMetrics metrics) {
        this.approvalRequestRepository = approvalRequestRepository;
        this.metrics = metrics;
    }

    @TransactionalEventListener
    public void onApprovalDecided(ApprovalDecidedEvent event) {
        approvalRequestRepository.findById(event.approvalId()).ifPresent(metrics::approvalDecided);
    }
}
