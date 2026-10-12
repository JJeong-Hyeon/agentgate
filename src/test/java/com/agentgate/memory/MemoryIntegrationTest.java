package com.agentgate.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentgate.agent.domain.Agent;
import com.agentgate.agent.repository.AgentRepository;
import com.agentgate.common.security.ApiKeyGenerator;
import com.agentgate.memory.embedding.EmbeddingClient;
import com.agentgate.memory.embedding.EmbeddingUnavailableException;
import com.agentgate.memory.service.MemoryEmbeddingBackfill;
import com.agentgate.migration.PostgresSchema;
import com.agentgate.user.repository.UserRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Agent memory on PostgreSQL with pgvector: semantic search, keyword fallback, backfill and access rules. */
@SpringBootTest(properties = {
        "agentgate.runtime.base-url=http://localhost:1",
        "agentgate.runtime.token=runtime-secret"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemoryIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = PostgresSchema.startWithPgvector();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresSchema.register(registry, POSTGRES);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AgentRepository agentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MemoryEmbeddingBackfill backfill;

    @MockitoBean
    private EmbeddingClient embeddingClient;

    @BeforeEach
    void setUp() {
        cleanUp();
        agentRepository.save(new Agent("support-agent", "Support", ApiKeyGenerator.hash("k")));
        when(embeddingClient.isEnabled()).thenReturn(true);
        when(embeddingClient.model()).thenReturn("toy-embedding");
        // A toy model with three topics, so "related" does not mean "shares a word".
        when(embeddingClient.embed(anyString())).thenAnswer(call -> toyEmbedding(call.getArgument(0)));
        when(embeddingClient.embed(anyList())).thenAnswer(call ->
                call.<List<String>>getArgument(0).stream().map(MemoryIntegrationTest::toyEmbedding).toList());
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from agent_memories");
        agentRepository.deleteAll();
        userRepository.findAll().stream()
                .filter(u -> !u.getUsername().equals("test-admin"))
                .forEach(userRepository::delete);
    }

    private static float[] toyEmbedding(String text) {
        if (text.contains("커피") || text.contains("카페") || text.contains("라떼")) {
            return new float[] {1, 0, 0};
        }
        if (text.contains("환불") || text.contains("결제")) {
            return new float[] {0, 1, 0};
        }
        return new float[] {0, 0, 1};
    }

    private ResultActions runtime(String path, String body) throws Exception {
        return mockMvc.perform(post(path).header("X-Runtime-Token", "runtime-secret")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void remember(String content) throws Exception {
        runtime("/api/v1/runtime/memories", """
                {"agentId":"support-agent","content":"%s","executionId":"exec-1"}""".formatted(content))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memory.source").value("AGENT"))
                .andExpect(jsonPath("$.memory.executionId").value("exec-1"));
    }

    private ResultActions search(String query) throws Exception {
        return runtime("/api/v1/runtime/memories/search", """
                {"agentId":"support-agent","query":"%s","limit":3}""".formatted(query));
    }

    @Test
    void migrationAddsTheVectorColumnWhenPgvectorIsAvailable() {
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_name = 'agent_memories' and column_name = 'embedding'""", Long.class)).isEqualTo(1L);
    }

    @Test
    void semanticSearchFindsRelatedMemoriesWithoutSharedWords() throws Exception {
        remember("김대리는 아이스 라떼를 좋아한다");
        remember("환불은 결제일로부터 7일 이내에만 가능하다");

        search("카페 음료 취향")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("김대리는 아이스 라떼를 좋아한다"))
                .andExpect(jsonPath("$[0].matchedBy").value("SEMANTIC"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void keywordSearchMatchesKoreanWordsWithParticlesWhenEmbeddingIsDown() throws Exception {
        remember("고객이 배송 지연을 문의했다");
        when(embeddingClient.embed(anyString())).thenThrow(new EmbeddingUnavailableException("down"));

        search("고객 배송")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("고객이 배송 지연을 문의했다"))
                .andExpect(jsonPath("$[0].matchedBy").value("KEYWORD"))
                .andExpect(jsonPath("$[0].score").value(1.0));
    }

    @Test
    void memoriesSavedWhileEmbeddingIsDownAreBackfilled() throws Exception {
        when(embeddingClient.embed(anyString())).thenThrow(new EmbeddingUnavailableException("down"));
        remember("팀장님은 매일 카페에서 회의한다");
        assertThat(jdbc.queryForObject("select count(*) from agent_memories where embedding is null", Long.class))
                .isEqualTo(1L);

        doAnswer(call -> toyEmbedding(call.getArgument(0))).when(embeddingClient).embed(anyString());
        backfill.embedPending();

        assertThat(jdbc.queryForObject("select count(*) from agent_memories where embedding is null", Long.class))
                .isZero();
        search("라떼").andExpect(jsonPath("$[0].matchedBy").value("SEMANTIC"));
    }

    @Test
    void runtimeEndpointsRequireTheRuntimeToken() throws Exception {
        mockMvc.perform(post("/api/v1/runtime/memories").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"support-agent\",\"content\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/runtime/memories/search").header("X-Runtime-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"support-agent\",\"query\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownAgentIsNotFound() throws Exception {
        runtime("/api/v1/runtime/memories", "{\"agentId\":\"nobody\",\"content\":\"x\"}")
                .andExpect(status().isNotFound());
    }

    private ResultActions as(String user, String password, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(httpBasic(user, password)));
    }

    @Test
    void consoleListsSearchesAddsAndForgets() throws Exception {
        remember("환불은 결제일로부터 7일 이내에만 가능하다");
        String created = as("test-admin", "test-password", post("/api/v1/agents/support-agent/memories")
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"VIP 고객은 라떼 쿠폰을 받는다\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memory.source").value("USER"))
                .andExpect(jsonPath("$.memory.createdBy").value("test-admin"))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(created.replaceAll(".*\"memory\":\\{\"id\":(\\d+).*", "$1"));

        as("test-admin", "test-password", get("/api/v1/agents/support-agent/memories"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(id));
        as("test-admin", "test-password", get("/api/v1/agents/support-agent/memories").param("query", "커피"))
                .andExpect(jsonPath("$[0].id").value(id));

        as("test-admin", "test-password", delete("/api/v1/agents/support-agent/memories/" + id))
                .andExpect(status().isNoContent());
        as("test-admin", "test-password", delete("/api/v1/agents/support-agent/memories/" + id))
                .andExpect(status().isNotFound());
    }

    @Test
    void viewersMayReadButNotChangeMemories() throws Exception {
        as("test-admin", "test-password", post("/api/v1/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"viewer\",\"password\":\"correct-horse-1\",\"roles\":[\"VIEWER\"]}"))
                .andExpect(status().isCreated());

        as("viewer", "correct-horse-1", get("/api/v1/agents/support-agent/memories")).andExpect(status().isOk());
        as("viewer", "correct-horse-1", post("/api/v1/agents/support-agent/memories")
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"x\"}"))
                .andExpect(status().isForbidden());
        as("viewer", "correct-horse-1", delete("/api/v1/agents/support-agent/memories/1"))
                .andExpect(status().isForbidden());
    }

    private ResultActions save(String body) throws Exception {
        return runtime("/api/v1/runtime/memories", body);
    }

    private long idOf(ResultActions result) throws Exception {
        return Long.parseLong(result.andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"memory\":\\{\"id\":(\\d+).*", "$1"));
    }

    @Test
    void sameTextIsNotStoredTwice() throws Exception {
        long first = idOf(save("{\"agentId\":\"support-agent\",\"content\":\"회의록은 노션 팀 위키에 올린다.\"}")
                .andExpect(status().isCreated()));

        save("{\"agentId\":\"support-agent\",\"content\":\"  회의록은 노션 팀위키에 올린다 \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.memory.id").value(first));

        assertThat(jdbc.queryForObject("select count(*) from agent_memories", Long.class)).isEqualTo(1L);
    }

    @Test
    void anUpdatedFactReplacesTheOldOne() throws Exception {
        long old = idOf(save("{\"agentId\":\"support-agent\",\"content\":\"팀 커피 타임은 오전 10시다\"}"));

        long updated = idOf(save("{\"agentId\":\"support-agent\",\"content\":\"팀 커피 타임은 오후 2시다\",\"replaces\":%d}"
                .formatted(old))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replaced").value(old))
                .andExpect(jsonPath("$.similar").isEmpty()));

        // The old memory stays for the record but is no longer searched, recalled or listed.
        assertThat(jdbc.queryForObject("select superseded_by from agent_memories where id = ?", Long.class, old))
                .isEqualTo(updated);
        search("커피").andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(updated));
        as("test-admin", "test-password", get("/api/v1/agents/support-agent/memories"))
                .andExpect(jsonPath("$.length()").value(1));
        // Replacing it again is not possible: it is no longer active.
        save("{\"agentId\":\"support-agent\",\"content\":\"x\",\"replaces\":%d}".formatted(old))
                .andExpect(status().isNotFound());
    }

    @Test
    void savingReportsSimilarMemoriesSoTheAgentCanDecide() throws Exception {
        long latte = idOf(save("{\"agentId\":\"support-agent\",\"content\":\"김대리는 아이스 라떼를 좋아한다\"}"));
        save("{\"agentId\":\"support-agent\",\"content\":\"환불은 7일 이내\"}");

        save("{\"agentId\":\"support-agent\",\"content\":\"김대리는 따뜻한 카페 라떼를 좋아한다\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.similar.length()").value(1))
                .andExpect(jsonPath("$.similar[0].id").value(latte));
    }

    @Test
    void replacingWithTextAlreadyRememberedKeepsTheExistingOne() throws Exception {
        long kept = idOf(save("{\"agentId\":\"support-agent\",\"content\":\"회의록은 노션에 올린다\"}"));
        long old = idOf(save("{\"agentId\":\"support-agent\",\"content\":\"회의록은 메일로 공유한다\"}"));

        save("{\"agentId\":\"support-agent\",\"content\":\"회의록은 노션에 올린다\",\"replaces\":%d}".formatted(old))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.memory.id").value(kept))
                .andExpect(jsonPath("$.replaced").value(old));
        assertThat(jdbc.queryForObject("select superseded_by from agent_memories where id = ?", Long.class, old))
                .isEqualTo(kept);
    }
}
