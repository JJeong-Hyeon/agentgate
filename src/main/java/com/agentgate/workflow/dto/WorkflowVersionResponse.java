package com.agentgate.workflow.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowVersionResponse(String workflowId, int version, Instant createdAt, JsonNode dsl) {
}
