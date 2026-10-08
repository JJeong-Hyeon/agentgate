package com.agentgate.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentDefinitionResponse(String agentId, int version, Instant createdAt, AgentDefinition definition) {
}
