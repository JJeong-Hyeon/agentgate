package com.agentgate.approval.service;

import com.agentgate.approval.domain.ApprovalRequest;
import com.agentgate.approval.domain.ApprovalStatus;
import com.agentgate.approval.repository.ApprovalRequestRepository;
import com.agentgate.approval.runtime.ApprovalDecidedEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expires approval requests nobody decided on within the TTL, so waiting executions do not hang forever.
 * An expiry is a decision like any other: the runtime is told and treats it as a rejection.
 */
@Component
public class ApprovalExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ApprovalExpiryJob.class);

    private final ApprovalRequestRepository approvalRequestRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public ApprovalExpiryJob(ApprovalRequestRepository approvalRequestRepository, ApplicationEventPublisher eventPublisher,
                             @Value("${agentgate.approval.ttl:PT24H}") Duration ttl) {
        this(approvalRequestRepository, eventPublisher, ttl, Clock.systemUTC());
    }

    ApprovalExpiryJob(ApprovalRequestRepository approvalRequestRepository, ApplicationEventPublisher eventPublisher,
                      Duration ttl, Clock clock) {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("agentgate.approval.ttl must be positive");
        }
        this.approvalRequestRepository = approvalRequestRepository;
        this.eventPublisher = eventPublisher;
        this.ttl = ttl;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${agentgate.approval.expiry-check-delay:PT1M}")
    @Transactional
    public void expireOverdue() {
        Instant cutoff = clock.instant().minus(ttl);
        List<ApprovalRequest> overdue = approvalRequestRepository.findByStatusAndCreatedAtBefore(ApprovalStatus.PENDING, cutoff);
        for (ApprovalRequest approval : overdue) {
            approval.expire();
            eventPublisher.publishEvent(new ApprovalDecidedEvent(approval.getId()));
        }
        if (!overdue.isEmpty()) {
            log.info("Expired {} approval request(s) pending longer than {}", overdue.size(), ttl);
        }
    }
}
