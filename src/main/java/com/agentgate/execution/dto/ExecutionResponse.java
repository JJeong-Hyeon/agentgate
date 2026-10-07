package com.agentgate.execution.dto;

import com.agentgate.execution.domain.Execution;
import com.agentgate.execution.domain.ExecutionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExecutionResponse(
        String executionId,
        String workflowId,
        int workflowVersion,
        String task,
        ExecutionStatus status,
        Long waitingApprovalId,
        String error,
        Instant createdAt,
        Instant updatedAt,
        Instant finishedAt,
        List<NodeExecutionResponse> nodes
) {
    public static ExecutionResponse from(Execution execution, List<NodeExecutionResponse> nodes) {
        return new ExecutionResponse(execution.getExecutionId(), execution.getWorkflowId(),
                execution.getWorkflowVersion(), execution.getTask(), execution.getStatus(),
                execution.getWaitingApprovalId(), execution.getError(), execution.getCreatedAt(),
                execution.getUpdatedAt(), execution.getFinishedAt(), nodes);
    }
}
