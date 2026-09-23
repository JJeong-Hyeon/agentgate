package com.agentgate.policy.domain;

import com.agentgate.risk.RiskLevel;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "policies")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Policy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String actionType;

    private String label;

    @Enumerated(EnumType.STRING)
    private RiskLevel riskLevel;

    private Instant createdAt;

    public Policy(String actionType, String label, RiskLevel riskLevel) {
        this.actionType = actionType;
        this.label = label;
        this.riskLevel = riskLevel;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public void update(String actionType, String label, RiskLevel riskLevel) {
        this.actionType = actionType;
        this.label = label;
        this.riskLevel = riskLevel;
    }
}
