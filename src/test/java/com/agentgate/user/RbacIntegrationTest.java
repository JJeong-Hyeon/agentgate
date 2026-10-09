package com.agentgate.user;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentgate.agent.domain.Agent;
import com.agentgate.agent.repository.AgentDefinitionVersionRepository;
import com.agentgate.agent.repository.AgentRepository;
import com.agentgate.approval.domain.ApprovalRequest;
import com.agentgate.approval.repository.ApprovalRequestRepository;
import com.agentgate.common.security.ApiKeyGenerator;
import com.agentgate.risk.RiskLevel;
import com.agentgate.runtime.RuntimeClient;
import com.agentgate.runtime.WorkflowValidation;
import com.agentgate.user.domain.User;
import com.agentgate.user.repository.UserRepository;
import com.agentgate.workflow.repository.WorkflowRepository;
import com.agentgate.workflow.repository.WorkflowVersionRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Who may do what in the console, by role. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RbacIntegrationTest {

    private static final String PASSWORD = "correct-horse-1";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AgentRepository agentRepository;
    @Autowired private AgentDefinitionVersionRepository definitionRepository;
    @Autowired private ApprovalRequestRepository approvalRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowVersionRepository workflowVersionRepository;

    @MockitoBean
    private RuntimeClient runtimeClient;

    private Long agentId;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        when(runtimeClient.validateWorkflow(any())).thenReturn(new WorkflowValidation(true, List.of()));
        agentId = agentRepository.save(new Agent("rbac-agent", "Agent", ApiKeyGenerator.hash("k"))).getId();
        for (String role : List.of("EDITOR", "APPROVER", "VIEWER")) {
            createUser(role.toLowerCase(), "[\"%s\"]".formatted(role)).andExpect(status().isCreated());
        }
    }

    // The bootstrapped administrator (test-admin) stays; everything else goes.
    @AfterEach
    void cleanUp() {
        workflowVersionRepository.deleteAll();
        workflowRepository.deleteAll();
        approvalRepository.deleteAll();
        definitionRepository.deleteAll();
        agentRepository.deleteAll();
        userRepository.findAll().stream()
                .filter(u -> !u.getUsername().equals("test-admin"))
                .forEach(userRepository::delete);
    }

    private ResultActions as(String user, MockHttpServletRequestBuilder request) throws Exception {
        String password = user.equals("test-admin") ? "test-password" : PASSWORD;
        return mockMvc.perform(request.with(httpBasic(user, password)));
    }

    private ResultActions createUser(String username, String roles) throws Exception {
        return as("test-admin", post("/api/v1/users").contentType(MediaType.APPLICATION_JSON).content("""
                {"username":"%s","password":"%s","roles":%s}""".formatted(username, PASSWORD, roles)));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static final String WORKFLOW = """
            {"workflowId":"rbac","dsl":{"nodes":[{"id":"start","type":"START"},
             {"id":"a","type":"LLM","config":{"prompt":"{task}"}},{"id":"end","type":"END"}],
             "edges":[{"source":"start","target":"a"},{"source":"a","target":"end"}]}}""";

    @Test
    void theInitialAdministratorComesFromConfiguration() throws Exception {
        as("test-admin", get("/api/v1/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("test-admin"))
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"));
    }

    @Test
    void viewersOnlyRead() throws Exception {
        as("viewer", get("/api/v1/workflows")).andExpect(status().isOk());
        as("viewer", get("/api/v1/approvals")).andExpect(status().isOk());
        as("viewer", get("/api/v1/me")).andExpect(jsonPath("$.roles[0]").value("VIEWER"));
        as("viewer", json(post("/api/v1/workflows"), WORKFLOW)).andExpect(status().isForbidden());
        as("viewer", json(post("/api/v1/policies"), "{\"actionType\":\"X\",\"riskLevel\":\"LOW\"}"))
                .andExpect(status().isForbidden());
        as("viewer", get("/api/v1/users")).andExpect(status().isForbidden());
    }

    @Test
    void editorsBuildAndRunButDoNotConfigureOrApprove() throws Exception {
        as("editor", json(post("/api/v1/workflows"), WORKFLOW)).andExpect(status().isCreated());
        as("editor", json(put("/api/v1/agents/{id}/definition", agentId), "{\"systemPrompt\":\"x\",\"tools\":[]}"))
                .andExpect(status().isCreated());
        as("editor", json(post("/api/v1/agents"), "{\"agentId\":\"new\",\"name\":\"New\"}"))
                .andExpect(status().isForbidden());
        as("editor", post("/api/v1/agents/{id}/api-key", agentId)).andExpect(status().isForbidden());
        as("editor", json(post("/api/v1/policies"), "{\"actionType\":\"X\",\"riskLevel\":\"LOW\"}"))
                .andExpect(status().isForbidden());
        as("editor", json(put("/api/v1/tool-risks"), "{\"server\":\"s\",\"tool\":\"t\",\"riskLevel\":\"LOW\"}"))
                .andExpect(status().isForbidden());
        as("editor", json(post("/api/v1/mcp-servers"), "{\"name\":\"s\",\"url\":\"http://s/mcp\"}"))
                .andExpect(status().isForbidden());
        Long approval = pendingApproval();
        as("editor", post("/api/v1/approvals/{id}/approve", approval)).andExpect(status().isForbidden());
    }

    @Test
    void approversDecideAndAreRecordedAsTheDecider() throws Exception {
        Long approval = pendingApproval();
        as("approver", json(post("/api/v1/approvals/{id}/approve", approval), "{\"decidedBy\":\"someone-else\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decidedBy").value("approver"));
        as("approver", json(post("/api/v1/workflows"), WORKFLOW)).andExpect(status().isForbidden());
    }

    @Test
    void usersChangeTheirOwnPassword() throws Exception {
        as("viewer", json(put("/api/v1/me/password"), "{\"currentPassword\":\"wrong\",\"newPassword\":\"another-pass-2\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("currentPassword: does not match"));
        as("viewer", json(put("/api/v1/me/password"),
                "{\"currentPassword\":\"%s\",\"newPassword\":\"another-pass-2\"}".formatted(PASSWORD)))
                .andExpect(status().isNoContent());

        as("viewer", get("/api/v1/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me").with(httpBasic("viewer", "another-pass-2"))).andExpect(status().isOk());
    }

    @Test
    void disabledUsersCannotSignIn() throws Exception {
        Long id = userRepository.findByUsername("viewer").orElseThrow().getId();
        as("test-admin", json(put("/api/v1/users/{id}", id), "{\"username\":\"viewer\",\"roles\":[\"VIEWER\"],\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        as("viewer", get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void theLastAdministratorCannotBeRemoved() throws Exception {
        Long admin = userRepository.findByUsername("test-admin").orElseThrow().getId();

        as("test-admin", delete("/api/v1/users/{id}", admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("you cannot delete yourself"));
        as("test-admin", json(put("/api/v1/users/{id}", admin), "{\"username\":\"test-admin\",\"roles\":[\"VIEWER\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("at least one enabled administrator must remain"));

        createUser("second-admin", "[\"ADMIN\"]");
        // With another administrator, the second one may remove the first.
        Long second = userRepository.findByUsername("second-admin").orElseThrow().getId();
        as("second-admin", delete("/api/v1/users/{id}", second))
                .andExpect(jsonPath("$.message").value("you cannot delete yourself"));
    }

    @Test
    void rejectsDuplicateOrIncompleteUsers() throws Exception {
        createUser("viewer", "[\"VIEWER\"]")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_ALREADY_EXISTS"));
        as("test-admin", json(post("/api/v1/users"), "{\"username\":\"nopass\",\"roles\":[\"VIEWER\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("password: required for a new user"));
        as("test-admin", json(post("/api/v1/users"), "{\"username\":\"short\",\"password\":\"123\",\"roles\":[\"VIEWER\"]}"))
                .andExpect(status().isBadRequest());
        as("test-admin", json(post("/api/v1/users"), "{\"username\":\"noroles\",\"password\":\"long-enough-1\",\"roles\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void passwordsAreStoredHashed() {
        User viewer = userRepository.findByUsername("viewer").orElseThrow();
        org.assertj.core.api.Assertions.assertThat(viewer.getPasswordHash()).doesNotContain(PASSWORD).startsWith("$2");
    }

    private Long pendingApproval() {
        return approvalRepository.save(new ApprovalRequest("rbac-agent", "SEND", null, List.of(), RiskLevel.HIGH)).getId();
    }
}
