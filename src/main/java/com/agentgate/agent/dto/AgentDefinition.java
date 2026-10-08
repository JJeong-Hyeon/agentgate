package com.agentgate.agent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * What an agent is when a workflow runs it. The runtime gives the model the listed tools
 * (except BLOCKED ones) and loops over tool calls for at most {@code maxSteps} model turns.
 */
public record AgentDefinition(
        @Size(max = 1000) String description,
        // Null → the runtime's default model.
        @Size(max = 200) String model,
        @DecimalMin("0") @DecimalMax("2") Double temperature,
        @NotBlank @Size(max = 20000) String systemPrompt,
        @NotNull @Size(max = 50) List<@Valid @NotNull AgentToolDefinition> tools,
        @Min(1) @Max(50) Integer maxSteps,
        // JSON Schema the final answer must match; null → free text.
        JsonNode outputSchema
) {
    public static final int DEFAULT_MAX_STEPS = 8;

    /** Defaults filled in, so stored versions are explicit. */
    public AgentDefinition normalized() {
        List<AgentToolDefinition> normalizedTools = tools.stream()
                .map(t -> new AgentToolDefinition(t.server(), t.tool(), t.permission(),
                        t.labels() == null ? List.of() : t.labels()))
                .toList();
        return new AgentDefinition(description, model, temperature, systemPrompt, normalizedTools,
                maxSteps == null ? DEFAULT_MAX_STEPS : maxSteps, outputSchema);
    }
}
