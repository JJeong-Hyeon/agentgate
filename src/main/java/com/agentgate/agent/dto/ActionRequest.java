package com.agentgate.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ActionRequest(
        @NotBlank String agentId,
        @NotBlank String action,
        String target,
        List<String> labels,
        @Size(max = 64) String executionId,
        // Ask a human even when policy would allow the action (BLOCKED still blocks).
        Boolean requireApproval,
        // Shown to the approver, e.g. why a workflow asks for approval.
        @Size(max = 1000) String reason,
        // Definition version the agent runs; its tool permissions then apply on top of policies.
        @Positive Integer agentVersion,
        // Agents that delegated this work, outermost first, e.g. "supervisor>research-agent".
        @Size(max = 500) String delegatedBy,
        // SHA-256 of what the runtime will run (action, target, arguments), kept with the approval so the
        // runtime can check, right before running, that it runs what was approved.
        @Size(max = 64) String payloadHash,
        // The arguments as the approver should read them (secrets masked), up to 20,000 characters.
        @Size(max = 20000) String payloadPreview
) {
    public ActionRequest {
        labels = (labels == null) ? List.of() : List.copyOf(labels);
    }

    public ActionRequest(String agentId, String action, String target, List<String> labels) {
        this(agentId, action, target, labels, null, null, null, null, null, null, null);
    }

    public ActionRequest(String agentId, String action, String target, List<String> labels, String executionId,
                         Boolean requireApproval, String reason, Integer agentVersion, String delegatedBy) {
        this(agentId, action, target, labels, executionId, requireApproval, reason, agentVersion, delegatedBy, null,
                null);
    }

    public boolean approvalRequested() {
        return Boolean.TRUE.equals(requireApproval);
    }
}
