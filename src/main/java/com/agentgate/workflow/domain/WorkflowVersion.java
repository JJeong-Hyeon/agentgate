package com.agentgate.workflow.domain;

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

/** Immutable snapshot of a workflow's DSL. */
@Entity
@Table(name = "workflow_versions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"workflow_id", "version"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkflowVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_id")
    private Workflow workflow;

    @Column(nullable = false)
    private int version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String dsl;

    private Instant createdAt;

    public WorkflowVersion(Workflow workflow, int version, String dsl) {
        this.workflow = workflow;
        this.version = version;
        this.dsl = dsl;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
