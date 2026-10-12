package com.agentgate.memory.service;

import com.agentgate.agent.repository.AgentRepository;
import com.agentgate.common.exception.AgentNotFoundException;
import com.agentgate.common.exception.MemoryNotFoundException;
import com.agentgate.memory.dto.AgentMemory;
import com.agentgate.memory.dto.MemorySaveResult;
import com.agentgate.memory.embedding.EmbeddingClient;
import com.agentgate.memory.embedding.EmbeddingUnavailableException;
import com.agentgate.memory.repository.AgentMemoryRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agents' long-term memory. With an embedding model and pgvector, searches are semantic and topped up by
 * keyword matches; otherwise (or when the model is unreachable) they are keyword-only. Saving never fails
 * because of the embedding model: a memory it could not embed is embedded later by {@link MemoryEmbeddingBackfill}.
 */
@Service
public class MemoryService {

    static final int CONSOLE_LIMIT = 100;
    static final int SIMILAR_LIMIT = 3;
    // A same-text memory shares every term, so it ranks first among keyword matches.
    private static final int DUPLICATE_CANDIDATES = 20;

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private final AgentMemoryRepository repository;
    private final AgentRepository agentRepository;
    private final EmbeddingClient embeddingClient;
    private final double minScore;
    private final double similarScore;

    public MemoryService(AgentMemoryRepository repository, AgentRepository agentRepository,
                         EmbeddingClient embeddingClient,
                         @Value("${agentgate.memory.min-score:0.3}") double minScore,
                         @Value("${agentgate.memory.similar-score:0.85}") double similarScore) {
        this.repository = repository;
        this.agentRepository = agentRepository;
        this.embeddingClient = embeddingClient;
        this.minScore = minScore;
        this.similarScore = similarScore;
    }

    public MemorySaveResult save(String agentId, String content, String source, String executionId, String createdBy) {
        return save(agentId, content, source, executionId, createdBy, null);
    }

    /**
     * Stores {@code content} unless the agent already remembers it word for word. With {@code replaces}, that
     * earlier memory is marked as replaced: kept for the record, no longer recalled or searched.
     *
     * @throws MemoryNotFoundException when {@code replaces} is not an active memory of the agent
     */
    @Transactional
    public MemorySaveResult save(String agentId, String content, String source, String executionId, String createdBy,
                                 Long replaces) {
        requireAgent(agentId);
        String text = content.strip();
        if (replaces != null && repository.find(agentId, replaces).isEmpty()) {
            throw new MemoryNotFoundException(agentId, replaces);
        }
        AgentMemory existing = findSameText(agentId, text, replaces);
        if (existing != null) {
            // Asked to replace another memory with content already remembered: the existing one takes its place.
            boolean replaced = replaces != null && repository.supersede(agentId, replaces, existing.id());
            return new MemorySaveResult(existing, true, replaced ? replaces : null, List.of());
        }
        AgentMemory memory = repository.insert(agentId, text, source, executionId, createdBy);
        if (replaces != null) {
            repository.supersede(agentId, replaces, memory.id());
        }
        List<AgentMemory> similar = List.of();
        if (semanticSearchAvailable()) {
            try {
                float[] embedding = embeddingClient.embed(memory.content());
                repository.setEmbedding(memory.id(), embedding, embeddingClient.model());
                if (replaces == null) {
                    similar = repository.nearest(agentId, embedding, embeddingClient.model(), similarScore,
                                    SIMILAR_LIMIT + 1).stream()
                            .filter(m -> !m.id().equals(memory.id()))
                            .limit(SIMILAR_LIMIT)
                            .toList();
                }
            } catch (EmbeddingUnavailableException e) {
                log.warn("Memory {} saved without an embedding; it will be embedded later: {}", memory.id(), e.getMessage());
            }
        }
        return new MemorySaveResult(memory, false, replaces, similar);
    }

    /** An active memory with the same text (ignoring case, spaces and punctuation), other than {@code except}. */
    private AgentMemory findSameText(String agentId, String text, Long except) {
        String wanted = AgentMemoryRepository.normalized(text);
        if (wanted.isEmpty()) {
            return null;
        }
        List<String> terms = AgentMemoryRepository.terms(text);
        List<AgentMemory> candidates = terms.isEmpty()
                ? repository.recent(agentId, DUPLICATE_CANDIDATES)
                : repository.keyword(agentId, terms, List.of(), DUPLICATE_CANDIDATES);
        return candidates.stream()
                .filter(m -> !m.id().equals(except))
                .filter(m -> AgentMemoryRepository.normalized(m.content()).equals(wanted))
                .findFirst()
                .orElse(null);
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
