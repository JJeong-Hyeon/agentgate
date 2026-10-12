package com.agentgate.memory.service;

import com.agentgate.memory.embedding.EmbeddingClient;
import com.agentgate.memory.embedding.EmbeddingUnavailableException;
import com.agentgate.memory.repository.AgentMemoryRepository;
import com.agentgate.memory.repository.AgentMemoryRepository.PendingEmbedding;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Embeds memories that have no vector from the current model: saved while the model was unreachable,
 * added before it was configured, or embedded with a model that has since been replaced.
 */
@Component
public class MemoryEmbeddingBackfill {

    static final int BATCH_SIZE = 32;

    private static final Logger log = LoggerFactory.getLogger(MemoryEmbeddingBackfill.class);

    private final AgentMemoryRepository repository;
    private final EmbeddingClient embeddingClient;

    public MemoryEmbeddingBackfill(AgentMemoryRepository repository, EmbeddingClient embeddingClient) {
        this.repository = repository;
        this.embeddingClient = embeddingClient;
    }

    @Scheduled(fixedDelayString = "${agentgate.memory.backfill-delay:PT5M}")
    public void embedPending() {
        if (!embeddingClient.isEnabled() || !repository.supportsVectors()) {
            return;
        }
        int embedded = 0;
        List<PendingEmbedding> batch;
        while (!(batch = repository.needingEmbedding(embeddingClient.model(), BATCH_SIZE)).isEmpty()) {
            List<float[]> vectors;
            try {
                vectors = embeddingClient.embed(batch.stream().map(PendingEmbedding::content).toList());
            } catch (EmbeddingUnavailableException e) {
                log.warn("Memory embedding backfill paused: {}", e.getMessage());
                break;
            }
            for (int i = 0; i < batch.size(); i++) {
                repository.setEmbedding(batch.get(i).id(), vectors.get(i), embeddingClient.model());
            }
            embedded += batch.size();
        }
        if (embedded > 0) {
            log.info("Embedded {} memories with {}", embedded, embeddingClient.model());
        }
    }
}
