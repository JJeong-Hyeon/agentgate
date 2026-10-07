package com.agentgate.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentgate.execution.repository.ExecutionRepository;
import com.agentgate.execution.repository.NodeExecutionRepository;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExecutionIntegrationTest {

    private static final String TOKEN = "runtime-token";
    private static final String DSL = """
            {"nodes":[{"id":"start","type":"START"},{"id":"answer","type":"LLM","config":{"prompt":"{task}"}},
             {"id":"end","type":"END"}],"edges":[{"source":"start","target":"answer"},{"source":"answer","target":"end"}]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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

    @BeforeEach
    void setUp() throws Exception {
        nodeExecutionRepository.deleteAll();
        executionRepository.deleteAll();
        workflowVersionRepository.deleteAll();
        workflowRepository.deleteAll();
        when(runtimeClient.validateWorkflow(any())).thenReturn(new WorkflowValidation(true, List.of()));
        when(runtimeClient.acceptsToken(TOKEN)).thenReturn(true);
        admin(post("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflowId\":\"echo\",\"dsl\":%s}".formatted(DSL)))
                .andExpect(status().isCreated());
    }

    private ResultActions admin(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mockMvc.perform(request.with(httpBasic("test-admin", "test-password")));
    }

    private String start() throws Exception {
        MvcResult result = admin(post("/api/v1/executions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflowId\":\"echo\",\"task\":\"hello\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("executionId").asString();
    }

    private ResultActions event(String executionId, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/executions/" + executionId + "/events")
                .header("X-Runtime-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void startRecordsExecutionAndRunsLatestVersionOnRuntime() throws Exception {
        String executionId = start();

        verify(runtimeClient).startExecution(eq(executionId), eq("hello"),
                org.mockito.ArgumentMatchers.argThat(dsl -> dsl.get("version").asInt() == 1));
        admin(get("/api/v1/executions/" + executionId))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.workflowId").value("echo"))
                .andExpect(jsonPath("$.workflowVersion").value(1))
                .andExpect(jsonPath("$.nodes.length()").value(0));
    }

    @Test
    void runtimeFailureAtStartIsRecorded() throws Exception {
        doThrow(new RuntimeUnavailableException("down")).when(runtimeClient)
                .startExecution(anyString(), anyString(), any());

        admin(post("/api/v1/executions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflowId\":\"echo\",\"task\":\"hello\"}"))
                .andExpect(status().isServiceUnavailable());

        assertThat(executionRepository.findAll()).singleElement()
                .satisfies(e -> assertThat(e.getStatus().name()).isEqualTo("FAILED"));
    }

    @Test
    void unknownWorkflowCannotBeStarted() throws Exception {
        admin(post("/api/v1/executions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflowId\":\"ghost\",\"task\":\"hello\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void eventsBuildNodeHistoryThroughApprovalAndResume() throws Exception {
        String id = start();

        event(id, "{\"type\":\"NODE_STARTED\",\"nodeId\":\"answer\",\"step\":\"answer\",\"taskId\":\"t1\"}")
                .andExpect(status().isAccepted());
        event(id, "{\"type\":\"NODE_COMPLETED\",\"nodeId\":\"answer\",\"step\":\"answer\",\"taskId\":\"t1\","
                + "\"output\":\"{\\\"answer\\\": \\\"hi\\\"}\"}");
        event(id, "{\"type\":\"NODE_STARTED\",\"nodeId\":\"report\",\"step\":\"report.approval\",\"taskId\":\"t2\"}");
        event(id, "{\"type\":\"NODE_WAITING\",\"nodeId\":\"report\",\"step\":\"report.approval\",\"taskId\":\"t2\","
                + "\"approvalId\":7}");
        event(id, "{\"type\":\"EXECUTION_WAITING\",\"approvalId\":7}");

        admin(get("/api/v1/executions/" + id))
                .andExpect(jsonPath("$.status").value("WAITING_APPROVAL"))
                .andExpect(jsonPath("$.waitingApprovalId").value(7))
                .andExpect(jsonPath("$.nodes[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.nodes[0].output").value("{\"answer\": \"hi\"}"))
                .andExpect(jsonPath("$.nodes[1].status").value("WAITING"))
                .andExpect(jsonPath("$.nodes[1].approvalId").value(7));

        // Resume re-runs the paused step under the same task id.
        event(id, "{\"type\":\"NODE_STARTED\",\"nodeId\":\"report\",\"step\":\"report.approval\",\"taskId\":\"t2\"}");
        event(id, "{\"type\":\"NODE_COMPLETED\",\"nodeId\":\"report\",\"step\":\"report.approval\",\"taskId\":\"t2\"}");
        event(id, "{\"type\":\"EXECUTION_COMPLETED\"}");

        admin(get("/api/v1/executions/" + id))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.waitingApprovalId").doesNotExist())
                .andExpect(jsonPath("$.finishedAt").exists())
                .andExpect(jsonPath("$.nodes.length()").value(2))
                .andExpect(jsonPath("$.nodes[1].status").value("COMPLETED"));
    }

    @Test
    void executionFailureFailsRunningNodes() throws Exception {
        String id = start();
        event(id, "{\"type\":\"NODE_STARTED\",\"nodeId\":\"answer\",\"step\":\"answer\",\"taskId\":\"t1\"}");

        event(id, "{\"type\":\"EXECUTION_FAILED\",\"error\":\"llm down\"}");

        admin(get("/api/v1/executions/" + id))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error").value("llm down"))
                .andExpect(jsonPath("$.nodes[0].status").value("FAILED"));
    }

    @Test
    void eventsRequireRuntimeToken() throws Exception {
        String id = start();

        mockMvc.perform(post("/api/v1/executions/" + id + "/events").header("X-Runtime-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"EXECUTION_COMPLETED\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_RUNTIME_TOKEN"));
        mockMvc.perform(post("/api/v1/executions/" + id + "/events")
                        .with(httpBasic("test-admin", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"EXECUTION_COMPLETED\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void eventsForUnknownExecutionAreNotFound() throws Exception {
        event("ghost", "{\"type\":\"EXECUTION_COMPLETED\"}").andExpect(status().isNotFound());
    }

    @Test
    void listFiltersByWorkflow() throws Exception {
        start();

        admin(get("/api/v1/executions").param("workflowId", "echo"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].nodes").doesNotExist());
        admin(get("/api/v1/executions").param("workflowId", "other"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void streamSendsSnapshotThenUpdates() throws Exception {
        String id = start();

        MvcResult stream = admin(get("/api/v1/executions/" + id + "/stream"))
                .andExpect(request().asyncStarted())
                .andReturn();
        event(id, "{\"type\":\"NODE_STARTED\",\"nodeId\":\"answer\",\"step\":\"answer\",\"taskId\":\"t1\"}");
        event(id, "{\"type\":\"EXECUTION_COMPLETED\"}");

        String body = stream.getResponse().getContentAsString();
        assertThat(body).contains("event:snapshot").contains("\"status\":\"RUNNING\"");
        assertThat(body).contains("event:update").contains("\"type\":\"NODE_STARTED\"")
                .contains("\"status\":\"COMPLETED\"");
    }

    @Test
    void streamRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/executions/x/stream")).andExpect(status().isUnauthorized());
    }
}
