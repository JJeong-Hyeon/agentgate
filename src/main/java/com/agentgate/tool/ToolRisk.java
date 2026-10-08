package com.agentgate.tool;

import com.agentgate.risk.RiskLevel;
import java.util.List;

/** The tools of one MCP server and how risky AgentGate considers each. */
public record ToolRisk(
        String server,
        String source,
        String transport,
        String error,
        List<Tool> tools
) {
    public record Tool(
            String name,
            String title,
            String description,
            // AgentGate action for calls to it: MCP:<server>:<tool>
            String action,
            // Set for this tool (a policy on its action); null when not set.
            RiskLevel riskLevel,
            Long policyId,
            // What applies to a call without labels: riskLevel, or the default for unknown actions.
            RiskLevel effectiveRiskLevel,
            // From the server's annotations; a hint only.
            RiskLevel suggestedRiskLevel
    ) {
    }
}
