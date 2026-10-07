package com.agentgate.workflow.dto;

import com.agentgate.workflow.domain.Workflow;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowResponse(
        Long id,
        String workflowId,
        String name,
        int latestVersion,
        Instant createdAt,
        Instant updatedAt,
        JsonNode dsl
) {
    public static WorkflowResponse from(Workflow workflow, JsonNode latestDsl) {
        return new WorkflowResponse(workflow.getId(), workflow.getWorkflowId(), workflow.getName(),
                workflow.getLatestVersion(), workflow.getCreatedAt(), workflow.getUpdatedAt(), latestDsl);
    }
}
