package com.agentgate.agent.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record ActionRequest(
        @NotBlank String agentId,
        @NotBlank String action,
        String target,
        List<String> labels
) {
    public ActionRequest {
        labels = (labels == null) ? List.of() : List.copyOf(labels);
    }
}
