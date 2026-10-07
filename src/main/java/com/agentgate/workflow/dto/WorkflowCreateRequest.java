package com.agentgate.workflow.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.JsonNode;

public record WorkflowCreateRequest(
        @NotNull @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,63}$") String workflowId,
        String name,
        @NotNull JsonNode dsl
) {
}
