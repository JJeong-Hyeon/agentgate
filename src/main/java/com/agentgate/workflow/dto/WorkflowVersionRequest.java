package com.agentgate.workflow.dto;

import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

public record WorkflowVersionRequest(@NotNull JsonNode dsl) {
}
