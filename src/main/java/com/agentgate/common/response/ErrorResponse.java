package com.agentgate.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        // Field-level problems, e.g. workflow validation issues; omitted when there are none.
        @JsonInclude(JsonInclude.Include.NON_NULL) List<?> errors
) {
    public ErrorResponse(Instant timestamp, int status, String error, String code, String message, String path) {
        this(timestamp, status, error, code, message, path, null);
    }
}
