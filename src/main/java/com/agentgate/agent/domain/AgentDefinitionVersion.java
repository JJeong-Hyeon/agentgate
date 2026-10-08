package com.agentgate.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Immutable snapshot of what an agent is: model, prompt, tools and their permissions. */
@Entity
@Table(name = "agent_definition_versions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"agent_id", "version"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentDefinitionVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agent_id")
    private Agent agent;

    @Column(nullable = false)
    private int version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String definition;

    private Instant createdAt;

    public AgentDefinitionVersion(Agent agent, int version, String definition) {
        this.agent = agent;
        this.version = version;
        this.definition = definition;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
