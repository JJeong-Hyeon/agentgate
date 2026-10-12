package com.agentgate.memory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A memory the runtime saves for an agent during an execution. */
public record MemorySaveRequest(
        @NotBlank @Size(max = 255) String agentId,
        @NotBlank @Size(max = 4000) String content,
        @Size(max = 64) String executionId
) {
}
