package com.agentgate.execution.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/** One run of a workflow version on the agent runtime; its id is the runtime's execution (thread) id. */
@Entity
@Table(name = "executions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Execution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 64)
    private String executionId;

    private String workflowId;

    private int workflowVersion;

    @Column(length = 4000)
    private String task;

    @Enumerated(EnumType.STRING)
    private ExecutionStatus status;

    private Long waitingApprovalId;

    @Column(length = 4000)
    private String error;

    private Instant createdAt;

    private Instant updatedAt;

    private Instant finishedAt;

    public Execution(String executionId, String workflowId, int workflowVersion, String task) {
        this.executionId = executionId;
        this.workflowId = workflowId;
        this.workflowVersion = workflowVersion;
        this.task = task;
        this.status = ExecutionStatus.RUNNING;
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

    public void running() {
        this.status = ExecutionStatus.RUNNING;
        this.waitingApprovalId = null;
    }

    public void waitingForApproval(Long approvalId) {
        this.status = ExecutionStatus.WAITING_APPROVAL;
        this.waitingApprovalId = approvalId;
    }

    public void completed(Instant at) {
        this.status = ExecutionStatus.COMPLETED;
        this.waitingApprovalId = null;
        this.finishedAt = at;
    }

    public void failed(String error, Instant at) {
        this.status = ExecutionStatus.FAILED;
        this.error = truncate(error);
        this.finishedAt = at;
    }

    static String truncate(String text) {
        return (text == null || text.length() <= 4000) ? text : text.substring(0, 4000);
    }
}
