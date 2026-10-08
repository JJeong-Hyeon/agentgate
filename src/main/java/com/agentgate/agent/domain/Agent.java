package com.agentgate.agent.domain;

import com.agentgate.risk.RiskLevel;
import jakarta.persistence.Column;
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
@Table(name = "agents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Agent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String agentId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String apiKeyHash;

    @Enumerated(EnumType.STRING)
    private RiskLevel maxRiskLevel;

    // Copied from the latest definition so listings need not load it.
    @Column(length = 1000)
    private String description;

    // 0 until the first definition is saved.
    private int latestDefinitionVersion;

    private Instant createdAt;

    public Agent(String agentId, String name, String apiKeyHash) {
        this.agentId = agentId;
        this.name = name;
        this.apiKeyHash = apiKeyHash;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public void restrictTo(RiskLevel maxRiskLevel) {
        this.maxRiskLevel = maxRiskLevel;
    }

    public int nextDefinitionVersion(String description) {
        this.description = description;
        return ++latestDefinitionVersion;
    }
}
