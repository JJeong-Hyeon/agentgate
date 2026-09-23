package com.agentgate.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
}
