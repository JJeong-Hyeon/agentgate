package com.agentgate.runtime;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.List;

public record WorkflowValidation(boolean valid, List<Issue> errors) {

    // The runtime sends snake_case node_id; AgentGate responds with camelCase like its other APIs.
    public record Issue(String path, String message, @JsonAlias("node_id") String nodeId) {
    }
}
