package com.agentgate.execution.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One step the runtime ran. A step paused for approval is re-run under the same task id when resumed,
 * so it is updated in place rather than recorded twice.
 */
@Entity
@Table(name = "node_executions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"execution_id", "task_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NodeExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "execution_id")
    private Execution execution;

    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    /** DSL node id. */
    private String nodeId;

    /** Runtime step name; differs from nodeId for tool sub-steps such as {@code report.approval}. */
    private String step;

    @Enumerated(EnumType.STRING)
    private NodeStatus status;

    @Column(length = 4000)
    private String output;

    @Column(length = 4000)
    private String error;

    private Long approvalId;

    private Instant startedAt;

    private Instant finishedAt;

    public NodeExecution(Execution execution, String taskId, String nodeId, String step) {
        this.execution = execution;
        this.taskId = taskId;
        this.nodeId = nodeId;
        this.step = step;
    }

    public void started(Instant at) {
        this.status = NodeStatus.RUNNING;
        this.startedAt = at;
        this.finishedAt = null;
    }

    public void completed(String output, Instant at) {
        this.status = NodeStatus.COMPLETED;
        this.output = Execution.truncate(output);
        this.finishedAt = at;
    }

    public void waiting(Long approvalId) {
        this.status = NodeStatus.WAITING;
        this.approvalId = approvalId;
    }

    public void failed(String error, Instant at) {
        this.status = NodeStatus.FAILED;
        this.error = Execution.truncate(error);
        this.finishedAt = at;
    }
}
