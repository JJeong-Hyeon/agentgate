package com.agentgate.user.domain;

/** Where a user proves who they are. */
public enum AuthSource {
    /** The password stored in AgentGate. */
    LOCAL,
    /** The company identity provider (OpenID Connect); AgentGate holds no usable password. */
    OIDC
}
