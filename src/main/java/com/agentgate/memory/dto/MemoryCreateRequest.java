package com.agentgate.memory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A memory added in the console, e.g. a fact every run of the agent should be able to recall. */
public record MemoryCreateRequest(@NotBlank @Size(max = 4000) String content) {
}
