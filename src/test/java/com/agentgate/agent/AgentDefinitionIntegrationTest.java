package com.agentgate.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentgate.agent.domain.Agent;
import com.agentgate.agent.domain.ToolPermission;
import com.agentgate.agent.dto.AgentDefinition;
import com.agentgate.agent.dto.AgentDefinitionResponse;
import com.agentgate.agent.dto.AgentToolDefinition;
import com.agentgate.agent.repository.AgentDefinitionVersionRepository;
import com.agentgate.agent.repository.AgentRepository;
import com.agentgate.agent.service.AgentDefinitionService;
import com.agentgate.common.security.ApiKeyGenerator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentDefinitionIntegrationTest {

    private static final String DEFINITION = """
            {"description":"Finds and files notes","systemPrompt":"You are a careful assistant.",
             "tools":[{"server":"notes","tool":"save_note","permission":"APPROVAL"},
                      {"server":"notes","tool":"list_notes","permission":"AUTO","labels":["INTERNAL"]}]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private AgentDefinitionVersionRepository definitionRepository;

    @Autowired
    private AgentDefinitionService agentDefinitionService;

    private Long agentId;

    @BeforeEach
    void setUp() {
        cleanUp();
        agentId = agentRepository.save(new Agent("note-agent", "Note Agent", ApiKeyGenerator.hash("key"))).getId();
    }

    // Other tests delete agents; definitions reference them.
    @AfterEach
    void cleanUp() {
        definitionRepository.deleteAll();
        agentRepository.deleteAll();
    }

    private ResultActions putDefinition(String body) throws Exception {
        return mockMvc.perform(put("/api/v1/agents/{id}/definition", agentId)
                .with(httpBasic("test-admin", "test-password"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions getJson(String url, Object... vars) throws Exception {
        return mockMvc.perform(get(url, vars).with(httpBasic("test-admin", "test-password")));
    }

    @Test
    void savesVersionsWithDefaultsFilledIn() throws Exception {
        putDefinition(DEFINITION)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.agentId").value("note-agent"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.definition.maxSteps").value(AgentDefinition.DEFAULT_MAX_STEPS))
                .andExpect(jsonPath("$.definition.tools[0].labels").isEmpty());
        putDefinition(DEFINITION.replace("careful", "terse")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));

        getJson("/api/v1/agents/{id}/definition", agentId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.definition.systemPrompt").value("You are a terse assistant."))
                .andExpect(jsonPath("$.definition.tools[1].permission").value("AUTO"));
        getJson("/api/v1/agents/{id}/definition/versions/1", agentId)
                .andExpect(jsonPath("$.definition.systemPrompt").value("You are a careful assistant."));
        getJson("/api/v1/agents/{id}/definition/versions", agentId)
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].definition").doesNotExist());
        getJson("/api/v1/agents/{id}", agentId)
                .andExpect(jsonPath("$.latestDefinitionVersion").value(2))
                .andExpect(jsonPath("$.description").value("Finds and files notes"));
    }

    @Test
    void returnsNotFoundBeforeFirstDefinition() throws Exception {
        getJson("/api/v1/agents/{id}/definition", agentId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AGENT_DEFINITION_NOT_FOUND"));
        getJson("/api/v1/agents/{id}/definition/versions/3", agentId)
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsInvalidDefinitions() throws Exception {
        putDefinition("""
                {"systemPrompt":"","tools":[]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        putDefinition("""
                {"systemPrompt":"x","tools":[{"server":"notes","tool":"save_note"}]}""")
                .andExpect(status().isBadRequest());
        putDefinition("""
                {"systemPrompt":"x","tools":[{"server":"bad server","tool":"t","permission":"AUTO"}]}""")
                .andExpect(status().isBadRequest());
        putDefinition("""
                {"systemPrompt":"x","maxSteps":0,"tools":[]}""")
                .andExpect(status().isBadRequest());
        putDefinition("""
                {"systemPrompt":"x","outputSchema":"string","tools":[]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("outputSchema: must be a JSON Schema object"));
        putDefinition("""
                {"systemPrompt":"x","tools":[{"server":"n","tool":"t","permission":"AUTO"},
                                             {"server":"n","tool":"t","permission":"BLOCKED"}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("tools: 'n/t' is listed more than once"));

        assertThat(definitionRepository.count()).isZero();
    }

    @Test
    void returnsNotFoundForUnknownAgent() throws Exception {
        mockMvc.perform(put("/api/v1/agents/{id}/definition", agentId + 1000)
                        .with(httpBasic("test-admin", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON).content(DEFINITION))
                .andExpect(status().isNotFound());
    }

    @Test
    void registeringAnExistingAgentIdIsAConflict() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/agents")
                        .with(httpBasic("test-admin", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"agentId\":\"note-agent\",\"name\":\"Again\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AGENT_ALREADY_EXISTS"));
    }

    @Test
    void requiresAdminAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/agents/{id}/definition", agentId)).andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentSavesGetDistinctVersions() throws Exception {
        AgentDefinition definition = new AgentDefinition(null, null, null, "prompt",
                List.of(new AgentToolDefinition("notes", "list_notes", ToolPermission.AUTO, null)), null, null, null);
        List<Callable<AgentDefinitionResponse>> tasks = IntStream.range(0, 8)
                .<Callable<AgentDefinitionResponse>>mapToObj(i -> () -> agentDefinitionService.save(agentId, definition))
                .toList();
        List<Integer> versions;
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            versions = pool.invokeAll(tasks).stream().map(this::versionOf).sorted().toList();
        }

        assertThat(versions).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
    }

    private int versionOf(Future<AgentDefinitionResponse> future) {
        try {
            return future.get().version();
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }
}
