package com.agentgate.agent.domain;

/** What an agent may do with one of its tools, on top of what policies decide. */
public enum ToolPermission {
    /** Policies and risk decide. */
    AUTO,
    /** Always needs human approval, even when policies would allow it. */
    APPROVAL,
    /** Never runs, whatever policies say. */
    BLOCKED
}
