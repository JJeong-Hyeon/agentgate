package com.agentgate.memory.dto;

import java.time.Instant;

/**
 * Something an agent remembers across executions.
 *
 * @param source  AGENT (saved by the agent during an execution) or USER (added in the console)
 * @param score   relevance to a search, higher is closer; null outside searches
 * @param matchedBy SEMANTIC (embedding similarity) or KEYWORD; null outside searches
 */
public record AgentMemory(
        Long id,
        String agentId,
        String content,
        String source,
        String executionId,
        String createdBy,
        Instant createdAt,
        Double score,
        String matchedBy
) {
    public static final String AGENT = "AGENT";
    public static final String USER = "USER";

    public AgentMemory scored(double score, String matchedBy) {
        return new AgentMemory(id, agentId, content, source, executionId, createdBy, createdAt, score, matchedBy);
    }
}
