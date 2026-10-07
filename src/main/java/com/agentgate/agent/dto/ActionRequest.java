package com.agentgate.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ActionRequest(
        @NotBlank String agentId,
        @NotBlank String action,
        String target,
        List<String> labels,
        @Size(max = 64) String executionId
) {
    public ActionRequest {
        labels = (labels == null) ? List.of() : List.copyOf(labels);
    }

    public ActionRequest(String agentId, String action, String target, List<String> labels) {
        this(agentId, action, target, labels, null);
    }
}
