package com.agentgate.memory.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MemorySearchRequest(
        @NotBlank @Size(max = 255) String agentId,
        @NotBlank @Size(max = 2000) String query,
        // Null → 5.
        @Min(1) @Max(20) Integer limit
) {
    public static final int DEFAULT_LIMIT = 5;

    public int limitOrDefault() {
        return limit == null ? DEFAULT_LIMIT : limit;
    }
}
