package com.agentgate.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentgate.agent.domain.Agent;
import com.agentgate.agent.repository.AgentRepository;
import com.agentgate.common.security.ApiKeyGenerator;
import com.agentgate.memory.dto.AgentMemory;
import com.agentgate.memory.embedding.EmbeddingClient;
import com.agentgate.memory.service.MemoryEmbeddingBackfill;
import com.agentgate.memory.service.MemoryService;
import com.agentgate.migration.PostgresSchema;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Servers without pgvector: the migration still succeeds and memory works with keyword search. */
@SpringBootTest
@ActiveProfiles("test")
class MemoryWithoutPgvectorTest {

    private static final PostgreSQLContainer POSTGRES = PostgresSchema.start();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresSchema.register(registry, POSTGRES);
    }

    @Autowired private MemoryService memoryService;
    @Autowired private MemoryEmbeddingBackfill backfill;
    @Autowired private AgentRepository agentRepository;

    @MockitoBean
    private EmbeddingClient embeddingClient;

    @Test
    void keywordSearchWorksAndTheEmbeddingModelIsNotUsed() {
        agentRepository.save(new Agent("plain-agent", "Plain", ApiKeyGenerator.hash("k")));
        when(embeddingClient.isEnabled()).thenReturn(true);
        when(embeddingClient.model()).thenReturn("toy-embedding");

        memoryService.save("plain-agent", "월말 정산 보고서는 매달 25일에 보낸다", AgentMemory.AGENT, "exec-1", "plain-agent");
        backfill.embedPending();
        List<AgentMemory> found = memoryService.search("plain-agent", "정산 보고서 일정", 5);

        assertThat(memoryService.semanticSearchAvailable()).isFalse();
        assertThat(found).extracting(AgentMemory::content).containsExactly("월말 정산 보고서는 매달 25일에 보낸다");
        assertThat(found.getFirst().matchedBy()).isEqualTo("KEYWORD");
        verify(embeddingClient, never()).embed(anyString());
    }
}
