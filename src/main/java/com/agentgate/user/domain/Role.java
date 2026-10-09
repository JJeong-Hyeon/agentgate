package com.agentgate.user.domain;

/**
 * What a console user may do. Roles do not include one another, except that ADMIN may do
 * everything; a user may hold several.
 */
public enum Role {
    /** System settings: users, policies, tool risks, MCP servers, agent registration and keys. */
    ADMIN,
    /** Edit workflows and agent definitions, and run executions. */
    EDITOR,
    /** Approve or reject actions waiting for a human. */
    APPROVER,
    /** Read everything. */
    VIEWER
}
