package com.agentgate.agent.dto;

import com.agentgate.agent.domain.ToolPermission;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * What an agent may do with its long-term memory. AgentGate evaluates searching as action
 * {@code MEMORY:SEARCH} with {@code read} and saving as {@code MEMORY:SAVE} with {@code write}, so policies
 * on those actions (risk, approval) apply as for any tool.
 *
 * @param recall memories the runtime looks up with the agent's task and gives it at the start of each run;
 *               needs {@code read} AUTO (null → 0)
 */
public record AgentMemoryDefinition(
        @NotNull ToolPermission read,
        @NotNull ToolPermission write,
        @Min(0) @Max(10) Integer recall
) {
    public static final String SEARCH_ACTION = "MEMORY:SEARCH";
    public static final String SAVE_ACTION = "MEMORY:SAVE";

    public AgentMemoryDefinition normalized() {
        return new AgentMemoryDefinition(read, write, recall == null ? 0 : recall);
    }

    /** The permission for a memory action, if {@code action} is one. */
    public ToolPermission permissionFor(String action) {
        return switch (action) {
            case SEARCH_ACTION -> read;
            case SAVE_ACTION -> write;
            default -> null;
        };
    }
}
