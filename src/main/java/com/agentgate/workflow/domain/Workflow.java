package com.agentgate.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "workflows")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Workflow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String workflowId;

    private String name;

    private int latestVersion;

    private Instant createdAt;

    private Instant updatedAt;

    public Workflow(String workflowId, String name) {
        this.workflowId = workflowId;
        this.name = name;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public int nextVersion() {
        return ++latestVersion;
    }

    public void rename(String name) {
        this.name = name;
    }
}
