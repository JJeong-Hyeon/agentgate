package com.agentgate.approval.runtime;

import com.agentgate.approval.domain.ApprovalRequest;
import com.agentgate.approval.domain.ApprovalStatus;
import com.agentgate.approval.repository.ApprovalRequestRepository;
import com.agentgate.runtime.RuntimeClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tells the runtime about approval decisions for executions it is waiting on.
 * Delivery is attempted right after the decision commits and retried periodically until it succeeds.
 */
@Component
public class RuntimeResumeNotifier {

    private final ApprovalRequestRepository approvalRequestRepository;
    private final RuntimeClient runtimeClient;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public RuntimeResumeNotifier(ApprovalRequestRepository approvalRequestRepository, RuntimeClient runtimeClient,
                                 PlatformTransactionManager transactionManager) {
        this(approvalRequestRepository, runtimeClient, requiresNew(transactionManager));
    }

    RuntimeResumeNotifier(ApprovalRequestRepository approvalRequestRepository, RuntimeClient runtimeClient,
                          TransactionTemplate transactionTemplate) {
        this.approvalRequestRepository = approvalRequestRepository;
        this.runtimeClient = runtimeClient;
        this.transactionTemplate = transactionTemplate;
    }

    // After commit, the decision's transaction is still bound to the thread; REQUIRED would join it and
    // the update would never be committed.
    private static TransactionTemplate requiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    @TransactionalEventListener
    public void onApprovalDecided(ApprovalDecidedEvent event) {
        notifyRuntime(event.approvalId());
    }

    @Scheduled(fixedDelayString = "${agentgate.runtime.retry-delay:PT30S}")
    public void retryUndelivered() {
        if (!runtimeClient.isEnabled()) {
            return;
        }
        approvalRequestRepository
                .findByExecutionIdIsNotNullAndStatusNotAndRuntimeNotifiedAtIsNull(ApprovalStatus.PENDING)
                .forEach(approval -> notifyRuntime(approval.getId()));
    }

    void notifyRuntime(Long approvalId) {
        if (!runtimeClient.isEnabled()) {
            return;
        }
        ApprovalRequest approval = approvalRequestRepository.findById(approvalId).orElse(null);
        if (approval == null || !approval.needsRuntimeNotification()) {
            return;
        }
        if (runtimeClient.resume(approval.getExecutionId(), approval.getId(), approval.getStatus())) {
            transactionTemplate.executeWithoutResult(status ->
                    approvalRequestRepository.findById(approvalId).ifPresent(ApprovalRequest::markRuntimeNotified));
        }
    }
}
