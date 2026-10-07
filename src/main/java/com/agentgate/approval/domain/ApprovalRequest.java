package com.agentgate.approval.domain;

import com.agentgate.common.exception.IllegalApprovalStateException;
import com.agentgate.risk.RiskLevel;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "approval_requests", indexes = @Index(columnList = "execution_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApprovalRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String agentId;

    private String action;

    private String target;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "approval_request_labels", joinColumns = @JoinColumn(name = "approval_request_id"))
    private List<String> labels = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    private RiskLevel riskLevel;

    @Enumerated(EnumType.STRING)
    private ApprovalStatus status;

    private Instant createdAt;

    private Instant decidedAt;

    private String decidedBy;

    // Runtime execution (LangGraph thread) waiting on this approval; null for direct API callers.
    private String executionId;

    private Instant runtimeNotifiedAt;

    public ApprovalRequest(String agentId, String action, String target, List<String> labels, RiskLevel riskLevel) {
        this(agentId, action, target, labels, riskLevel, null);
    }

    public ApprovalRequest(String agentId, String action, String target, List<String> labels, RiskLevel riskLevel,
                           String executionId) {
        this.executionId = executionId;
        this.agentId = agentId;
        this.action = action;
        this.target = target;
        this.labels = new ArrayList<>(labels);
        this.riskLevel = riskLevel;
        this.status = ApprovalStatus.PENDING;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public void approve(String decidedBy) {
        transitionTo(ApprovalStatus.APPROVED, decidedBy);
    }

    public void reject(String decidedBy) {
        transitionTo(ApprovalStatus.REJECTED, decidedBy);
    }

    public boolean needsRuntimeNotification() {
        return executionId != null && status != ApprovalStatus.PENDING && runtimeNotifiedAt == null;
    }

    public void markRuntimeNotified() {
        this.runtimeNotifiedAt = Instant.now();
    }

    private void transitionTo(ApprovalStatus newStatus, String decidedBy) {
        if (this.status != ApprovalStatus.PENDING) {
            throw new IllegalApprovalStateException(this.id, this.status);
        }
        this.status = newStatus;
        this.decidedBy = decidedBy;
        this.decidedAt = Instant.now();
    }
}
