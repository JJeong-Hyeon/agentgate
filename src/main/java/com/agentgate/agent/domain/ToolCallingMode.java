package com.agentgate.agent.domain;

/** How the runtime lets an agent's model call tools. */
public enum ToolCallingMode {
    /** OpenAI-compatible function calling; for servers and models that support it. */
    NATIVE,
    /** Tools described in the prompt, calls read from the model's JSON reply. */
    JSON
}
