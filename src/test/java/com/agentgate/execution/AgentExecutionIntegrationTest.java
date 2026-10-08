package com.agentgate.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentgate.agent.domain.Agent;
import com.agentgate.agent.repository.AgentDefinitionVersionRepository;
import com.agentgate.agent.repository.AgentRepository;
import com.agentgate.common.security.ApiKeyGenerator;
import com.agentgate.execution.repository.ExecutionRepository;
import com.agentgate.execution.repository.NodeExecutionRepository;
import com.agentgate.runtime.RuntimeClient;
import com.agentgate.runtime.WorkflowValidation;
import com.agentgate.workflow.repository.WorkflowRepository;
import com.agentgate.workflow.repository.WorkflowVersionRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Workflows whose AGENT nodes run registered agent definitions. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentExecutionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private AgentDefinitionVersionRepository definitionRepository;

    @Autowired
    private ExecutionRepository executionRepository;

    @Autowired
    private NodeExecutionRepository nodeExecutionRepository;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowVersionRepository workflowVersionRepository;

    @MockitoBean
    private RuntimeClient runtimeClient;

    private Long agentId;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        when(runtimeClient.validateWorkflow(any())).thenReturn(new WorkflowValidation(true, List.of()));
        agentId = agentRepository.save(new Agent("note-agent", "Note Agent", ApiKeyGenerator.hash("k"))).getId();
        saveDefinition("You are careful.");
        saveDefinition("You are terse.");
    }

    @AfterEach
    void cleanUp() {
        nodeExecutionRepository.deleteAll();
        executionRepository.deleteAll();
        workflowVersionRepository.deleteAll();
        workflowRepository.deleteAll();
        definitionRepository.deleteAll();
        agentRepository.deleteAll();
    }

    private ResultActions admin(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(httpBasic("test-admin", "test-password")));
    }

    private void saveDefinition(String systemPrompt) throws Exception {
        admin(put("/api/v1/agents/{id}/definition", agentId).contentType(MediaType.APPLICATION_JSON).content("""
                {"systemPrompt":"%s","tools":[{"server":"notes","tool":"save_note","permission":"APPROVAL"}]}
                """.formatted(systemPrompt))).andExpect(status().isCreated());
    }

    private static String workflow(String agentConfig) {
        return """
                {"workflowId":"notes","dsl":{"nodes":[{"id":"start","type":"START"},
                  {"id":"helper","type":"AGENT","config":%s},{"id":"end","type":"END"}],
                 "edges":[{"source":"start","target":"helper"},{"source":"helper","target":"end"}]}}
                """.formatted(agentConfig);
    }

    private ResultActions createWorkflow(String agentConfig) throws Exception {
        return admin(post("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON).content(workflow(agentConfig)));
    }

    private JsonNode startAndCaptureDsl() throws Exception {
        String body = admin(post("/api/v1/executions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowId\":\"notes\",\"task\":\"t\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.agentVersions.note-agent").exists())
                .andReturn().getResponse().getContentAsString();
        ArgumentCaptor<JsonNode> dsl = ArgumentCaptor.forClass(JsonNode.class);
        String executionId = objectMapper.readTree(body).get("executionId").asString();
        verify(runtimeClient).startExecution(eq(executionId), eq("t"), dsl.capture());
        return dsl.getValue();
    }

    @Test
    void runsTheLatestDefinitionAndRecordsItsVersion() throws Exception {
        createWorkflow("{\"agentId\":\"note-agent\",\"prompt\":\"{task}\"}").andExpect(status().isCreated());

        JsonNode agent = startAndCaptureDsl().get("agents").get("note-agent");

        assertThat(agent.get("agentId").asString()).isEqualTo("note-agent");
        assertThat(agent.get("version").asInt()).isEqualTo(2);
        assertThat(agent.get("systemPrompt").asString()).isEqualTo("You are terse.");
        assertThat(agent.get("maxSteps").asInt()).isEqualTo(8);
        assertThat(agent.get("tools").get(0).get("permission").asString()).isEqualTo("APPROVAL");
        String executionId = executionRepository.findAll().get(0).getExecutionId();
        admin(get("/api/v1/executions/" + executionId)).andExpect(jsonPath("$.agentVersions.note-agent").value(2));
    }

    @Test
    void passesTheToolCallingModeAndOutputSchema() throws Exception {
        admin(put("/api/v1/agents/{id}/definition", agentId).contentType(MediaType.APPLICATION_JSON).content("""
                {"systemPrompt":"x","tools":[],"toolCalling":"JSON",
                 "outputSchema":{"type":"object","required":["total"]}}
                """)).andExpect(status().isCreated());
        createWorkflow("{\"agentId\":\"note-agent\",\"prompt\":\"{task}\"}").andExpect(status().isCreated());

        JsonNode agent = startAndCaptureDsl().get("agents").get("note-agent");

        assertThat(agent.get("toolCalling").asString()).isEqualTo("JSON");
        assertThat(agent.get("outputSchema").get("required").get(0).asString()).isEqualTo("total");
    }

    @Test
    void runsAPinnedVersion() throws Exception {
        createWorkflow("{\"agentId\":\"note-agent\",\"agentVersion\":1,\"prompt\":\"{task}\"}")
                .andExpect(status().isCreated());

        JsonNode agent = startAndCaptureDsl().get("agents").get("note-agent");

        assertThat(agent.get("version").asInt()).isEqualTo(1);
        assertThat(agent.get("systemPrompt").asString()).isEqualTo("You are careful.");
    }

    @Test
    void workflowsWithoutAgentsAreSentUnchanged() throws Exception {
        createWorkflow("{\"prompt\":\"{task}\",\"system\":\"inline\"}").andExpect(status().isCreated());

        admin(post("/api/v1/executions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowId\":\"notes\",\"task\":\"t\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.agentVersions").doesNotExist());
        verify(runtimeClient).startExecution(anyString(), eq("t"),
                org.mockito.ArgumentMatchers.argThat(dsl -> !dsl.has("agents")));
    }

    @Test
    void savingRejectsReferencesThatCannotRun() throws Exception {
        createWorkflow("{\"agentId\":\"ghost\",\"prompt\":\"x\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].nodeId").value("helper"))
                .andExpect(jsonPath("$.errors[0].message").value("Unknown agent 'ghost'"));
        createWorkflow("{\"agentId\":\"note-agent\",\"agentVersion\":7,\"prompt\":\"x\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Agent 'note-agent' has no version 7"));

        agentRepository.save(new Agent("blank-agent", "Blank", ApiKeyGenerator.hash("k")));
        createWorkflow("{\"agentId\":\"blank-agent\",\"prompt\":\"x\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Agent 'blank-agent' has no definition yet"));
    }

    @Test
    void savingRejectsConflictingPins() throws Exception {
        String dsl = """
                {"workflowId":"notes","dsl":{"nodes":[{"id":"start","type":"START"},
                  {"id":"a","type":"AGENT","config":{"agentId":"note-agent","agentVersion":1,"prompt":"x"}},
                  {"id":"b","type":"AGENT","config":{"agentId":"note-agent","agentVersion":2,"prompt":"x"}},
                  {"id":"end","type":"END"}],
                 "edges":[{"source":"start","target":"a"},{"source":"a","target":"b"},{"source":"b","target":"end"}]}}
                """;
        admin(post("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON).content(dsl))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Agent 'note-agent' is pinned to different versions [1, 2]"));
    }

    @Test
    void startingFailsWhenTheAgentWasRemovedAfterSaving() throws Exception {
        createWorkflow("{\"agentId\":\"note-agent\",\"prompt\":\"{task}\"}").andExpect(status().isCreated());
        definitionRepository.deleteAll();
        agentRepository.deleteAll();

        admin(post("/api/v1/executions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowId\":\"notes\",\"task\":\"t\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Unknown agent 'note-agent'"));
        verify(runtimeClient, never()).startExecution(anyString(), anyString(), any());
        assertThat(executionRepository.count()).isZero();
    }
}
