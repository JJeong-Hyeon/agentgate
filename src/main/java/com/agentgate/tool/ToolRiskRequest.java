package com.agentgate.tool;

import com.agentgate.risk.RiskLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ToolRiskRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$") String server,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_.-]{1,128}$") String tool,
        @NotNull RiskLevel riskLevel
) {
}
