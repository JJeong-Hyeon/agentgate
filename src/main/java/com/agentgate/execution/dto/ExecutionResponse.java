package com.agentgate.execution.dto;

import com.agentgate.execution.domain.Execution;
import com.agentgate.execution.domain.ExecutionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

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
        // agentId → definition version the run uses
        Map<String, Integer> agentVersions,
        List<NodeExecutionResponse> nodes
) {
    private static final JsonMapper JSON = new JsonMapper();

    public static ExecutionResponse from(Execution execution, List<NodeExecutionResponse> nodes) {
        return new ExecutionResponse(execution.getExecutionId(), execution.getWorkflowId(),
                execution.getWorkflowVersion(), execution.getTask(), execution.getStatus(),
                execution.getWaitingApprovalId(), execution.getError(), execution.getCreatedAt(),
                execution.getUpdatedAt(), execution.getFinishedAt(), agentVersions(execution), nodes);
    }

    private static Map<String, Integer> agentVersions(Execution execution) {
        if (execution.getAgentVersions() == null) {
            return null;
        }
        return JSON.readValue(execution.getAgentVersions(), new TypeReference<Map<String, Integer>>() {
        });
    }
}
