package com.agentgate.memory.service;

import com.agentgate.agent.repository.AgentRepository;
import com.agentgate.common.exception.AgentNotFoundException;
import com.agentgate.common.exception.MemoryNotFoundException;
import com.agentgate.memory.dto.AgentMemory;
import com.agentgate.memory.embedding.EmbeddingClient;
import com.agentgate.memory.embedding.EmbeddingUnavailableException;
import com.agentgate.memory.repository.AgentMemoryRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Agents' long-term memory. With an embedding model and pgvector, searches are semantic and topped up by
 * keyword matches; otherwise (or when the model is unreachable) they are keyword-only. Saving never fails
 * because of the embedding model: a memory it could not embed is embedded later by {@link MemoryEmbeddingBackfill}.
 */
@Service
public class MemoryService {

    static final int CONSOLE_LIMIT = 100;

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private final AgentMemoryRepository repository;
    private final AgentRepository agentRepository;
    private final EmbeddingClient embeddingClient;
    private final double minScore;

    public MemoryService(AgentMemoryRepository repository, AgentRepository agentRepository,
                         EmbeddingClient embeddingClient,
                         @Value("${agentgate.memory.min-score:0.3}") double minScore) {
        this.repository = repository;
        this.agentRepository = agentRepository;
        this.embeddingClient = embeddingClient;
        this.minScore = minScore;
    }

    public AgentMemory save(String agentId, String content, String source, String executionId, String createdBy) {
        requireAgent(agentId);
        AgentMemory memory = repository.insert(agentId, content.strip(), source, executionId, createdBy);
        if (semanticSearchAvailable()) {
            try {
                repository.setEmbedding(memory.id(), embeddingClient.embed(memory.content()), embeddingClient.model());
            } catch (EmbeddingUnavailableException e) {
                log.warn("Memory {} saved without an embedding; it will be embedded later: {}", memory.id(), e.getMessage());
            }
        }
        return memory;
    }

    public List<AgentMemory> search(String agentId, String query, int limit) {
        requireAgent(agentId);
        List<AgentMemory> results = new ArrayList<>();
        if (semanticSearchAvailable()) {
            try {
                results.addAll(repository.nearest(agentId, embeddingClient.embed(query), embeddingClient.model(),
                        minScore, limit));
            } catch (EmbeddingUnavailableException e) {
                log.warn("Semantic memory search unavailable, using keywords only: {}", e.getMessage());
            }
        }
        if (results.size() < limit) {
            List<Long> found = results.stream().map(AgentMemory::id).toList();
            results.addAll(repository.keyword(agentId, AgentMemoryRepository.terms(query), found, limit - results.size()));
        }
        return results;
    }

    /** The console's view: the newest memories, or the best matches for {@code query}. */
    public List<AgentMemory> list(String agentId, String query) {
        if (query == null || query.isBlank()) {
            requireAgent(agentId);
            return repository.recent(agentId, CONSOLE_LIMIT);
        }
        return search(agentId, query, CONSOLE_LIMIT);
    }

    public void delete(String agentId, Long id) {
        requireAgent(agentId);
        if (!repository.delete(agentId, id)) {
            throw new MemoryNotFoundException(agentId, id);
        }
    }

    public boolean semanticSearchAvailable() {
        return embeddingClient.isEnabled() && repository.supportsVectors();
    }

    private void requireAgent(String agentId) {
        if (agentRepository.findByAgentId(agentId).isEmpty()) {
            throw new AgentNotFoundException(agentId);
        }
    }
}
