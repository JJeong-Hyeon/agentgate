package com.agentgate.execution.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ExecutionStartRequest(
        @NotBlank String workflowId,
        // Omitted → latest version.
        @Positive Integer version,
        @NotBlank @Size(max = 4000) String task
) {
}
