package com.agentgate.workflow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentgate.runtime.RuntimeClient;
import com.agentgate.runtime.RuntimeUnavailableException;
import com.agentgate.runtime.WorkflowValidation;
import com.agentgate.workflow.repository.WorkflowRepository;
import com.agentgate.workflow.repository.WorkflowVersionRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkflowIntegrationTest {

    private static final String DSL = """
            {"name":"Echo","nodes":[{"id":"start","type":"START"},
             {"id":"answer","type":"LLM","config":{"prompt":"{task}"}},{"id":"end","type":"END"}],
             "edges":[{"source":"start","target":"answer"},{"source":"answer","target":"end"}]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowVersionRepository workflowVersionRepository;

    @MockitoBean
    private RuntimeClient runtimeClient;

    @BeforeEach
    void setUp() {
        workflowVersionRepository.deleteAll();
        workflowRepository.deleteAll();
        when(runtimeClient.validateWorkflow(any())).thenReturn(new WorkflowValidation(true, List.of()));
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).with(httpBasic("test-admin", "test-password"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions getJson(String url) throws Exception {
        return mockMvc.perform(get(url).with(httpBasic("test-admin", "test-password")));
    }

    private ResultActions create() throws Exception {
        return postJson("/api/v1/workflows", """
                {"workflowId":"echo","dsl":%s}
                """.formatted(DSL));
    }

    @Test
    void createStoresVersionOneStampedWithIdAndVersion() throws Exception {
        create().andExpect(status().isCreated())
                .andExpect(jsonPath("$.workflowId").value("echo"))
                .andExpect(jsonPath("$.name").value("Echo"))
                .andExpect(jsonPath("$.latestVersion").value(1))
                .andExpect(jsonPath("$.dsl.workflowId").value("echo"))
                .andExpect(jsonPath("$.dsl.version").value(1));

        verify(runtimeClient).validateWorkflow(argThat(dsl ->
                dsl.get("workflowId").asString().equals("echo") && dsl.get("version").asInt() == 1));
    }

    @Test
    void newVersionIncrementsAndOldVersionStaysReadable() throws Exception {
        create();
        String changed = DSL.replace("{task}", "Answer: {task}");

        postJson("/api/v1/workflows/echo/versions", "{\"dsl\":%s}".formatted(changed))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));

        getJson("/api/v1/workflows/echo")
                .andExpect(jsonPath("$.latestVersion").value(2))
                .andExpect(jsonPath("$.dsl.nodes[1].config.prompt").value("Answer: {task}"));
        getJson("/api/v1/workflows/echo/versions/1")
                .andExpect(jsonPath("$.dsl.nodes[1].config.prompt").value("{task}"));
        getJson("/api/v1/workflows/echo/versions")
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].dsl").doesNotExist());
    }

    @Test
    void listOmitsDsl() throws Exception {
        create();

        getJson("/api/v1/workflows")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].workflowId").value("echo"))
                .andExpect(jsonPath("$[0].dsl").doesNotExist());
    }

    @Test
    void duplicateWorkflowIdIsConflict() throws Exception {
        create();

        create().andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKFLOW_ALREADY_EXISTS"));
    }

    @Test
    void invalidDslIsRejectedWithRuntimeErrors() throws Exception {
        when(runtimeClient.validateWorkflow(any())).thenReturn(new WorkflowValidation(false,
                List.of(new WorkflowValidation.Issue("nodes", "Not reachable from START", "answer"))));

        create().andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_WORKFLOW"))
                .andExpect(jsonPath("$.errors[0].nodeId").value("answer"))
                .andExpect(jsonPath("$.errors[0].message").value("Not reachable from START"));
        getJson("/api/v1/workflows/echo").andExpect(status().isNotFound());
    }

    @Test
    void unavailableRuntimeIsServiceUnavailable() throws Exception {
        when(runtimeClient.validateWorkflow(any())).thenThrow(new RuntimeUnavailableException("down"));

        create().andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RUNTIME_UNAVAILABLE"));
    }

    @Test
    void unknownWorkflowAndVersionAreNotFound() throws Exception {
        create();

        getJson("/api/v1/workflows/ghost").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKFLOW_NOT_FOUND"));
        getJson("/api/v1/workflows/echo/versions/9").andExpect(status().isNotFound());
    }

    @Test
    void invalidWorkflowIdIsBadRequest() throws Exception {
        postJson("/api/v1/workflows", "{\"workflowId\":\"Bad Id\",\"dsl\":%s}".formatted(DSL))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/workflows")).andExpect(status().isUnauthorized());
    }
}
