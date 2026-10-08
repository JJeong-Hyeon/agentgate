package com.agentgate.risk;

/** Which rule produced an action's final status. */
public enum DecisionBasis {
    /** Policies and risk level. */
    POLICY,
    /** The agent's maxRiskLevel turned the result into BLOCKED. */
    AGENT_RISK_CAP,
    /** The tool is not in the agent's definition. */
    TOOL_NOT_GRANTED,
    /** The agent's definition marks the tool BLOCKED. */
    TOOL_BLOCKED,
    /** The agent's definition requires approval for the tool. */
    TOOL_REQUIRES_APPROVAL,
    /** The caller asked for approval (requireApproval). */
    APPROVAL_REQUESTED
}
