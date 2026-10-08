package com.agentgate.agent.dto;

import java.time.Instant;

/** A newly issued API key; the plain key is only ever returned here. */
public record ApiKeyResponse(Long id, String agentId, String apiKey, Instant issuedAt) {
}
