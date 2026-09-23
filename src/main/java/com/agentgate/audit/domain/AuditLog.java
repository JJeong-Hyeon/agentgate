package com.agentgate.audit.domain;

import com.agentgate.risk.ActionStatus;
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
@Table(name = "audit_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String agentId;

    private String action;

    private String target;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "audit_log_labels", joinColumns = @JoinColumn(name = "audit_log_id"))
    private List<String> labels = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    private RiskLevel riskLevel;

    @Enumerated(EnumType.STRING)
    private ActionStatus status;

    private Long approvalId;

    private Instant createdAt;

    public AuditLog(String agentId, String action, String target, List<String> labels,
                     RiskLevel riskLevel, ActionStatus status, Long approvalId) {
        this.agentId = agentId;
        this.action = action;
        this.target = target;
        this.labels = new ArrayList<>(labels);
        this.riskLevel = riskLevel;
        this.status = status;
        this.approvalId = approvalId;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
