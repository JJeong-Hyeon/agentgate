package com.agentgate.approval.notification;

import com.agentgate.approval.domain.ApprovalRequest;
import com.agentgate.approval.domain.ApprovalStatus;
import com.agentgate.approval.repository.ApprovalRequestRepository;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tells approvers about new approval requests. Delivery runs off the caller's thread right after the request
 * commits, and is retried periodically while the request is still pending.
 */
@Component
public class ApprovalNotifier {

    // Leaves the first, asynchronous attempt alone so a retry does not send a duplicate.
    private static final Duration RETRY_GRACE = Duration.ofSeconds(30);

    private final ApprovalRequestRepository approvalRequestRepository;
    private final ApprovalWebhook webhook;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Autowired
    public ApprovalNotifier(ApprovalRequestRepository approvalRequestRepository, ApprovalWebhook webhook,
                            PlatformTransactionManager transactionManager) {
        this(approvalRequestRepository, webhook, requiresNew(transactionManager), Clock.systemUTC());
    }

    ApprovalNotifier(ApprovalRequestRepository approvalRequestRepository, ApprovalWebhook webhook,
                     TransactionTemplate transactionTemplate, Clock clock) {
        this.approvalRequestRepository = approvalRequestRepository;
        this.webhook = webhook;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    private static TransactionTemplate requiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    @Async
    @TransactionalEventListener
    public void onApprovalRequested(ApprovalRequestedEvent event) {
        notifyApprovers(event.approvalId());
    }

    @Scheduled(fixedDelayString = "${agentgate.notifications.retry-delay:PT30S}")
    public void retryUndelivered() {
        if (!webhook.isEnabled()) {
            return;
        }
        approvalRequestRepository
                .findByStatusAndApproverNotifiedAtIsNullAndCreatedAtBefore(ApprovalStatus.PENDING,
                        clock.instant().minus(RETRY_GRACE))
                .forEach(approval -> notifyApprovers(approval.getId()));
    }

    void notifyApprovers(Long approvalId) {
        if (!webhook.isEnabled()) {
            return;
        }
        ApprovalRequest approval = approvalRequestRepository.findById(approvalId).orElse(null);
        if (approval == null || !approval.needsApproverNotification()) {
            return;
        }
        if (webhook.send(approval)) {
            transactionTemplate.executeWithoutResult(status ->
                    approvalRequestRepository.findById(approvalId).ifPresent(ApprovalRequest::markApproverNotified));
        }
    }
}
