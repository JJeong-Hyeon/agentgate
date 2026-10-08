package com.agentgate.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.agentgate.approval.repository.ApprovalRequestRepository;
import com.agentgate.audit.repository.AuditLogRepository;
import com.agentgate.common.security.ApiKeyGenerator;
import com.agentgate.execution.repository.ExecutionRepository;
import com.agentgate.execution.repository.NodeExecutionRepository;
import com.agentgate.policy.domain.Policy;
import com.agentgate.policy.repository.PolicyRepository;
import com.agentgate.risk.RiskLevel;
import com.agentgate.runtime.RuntimeClient;
import com.agentgate.runtime.WorkflowValidation;
import com.agentgate.workflow.repository.WorkflowRepository;
import com.agentgate.workflow.repository.WorkflowVersionRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/** Supervisor agents delegating to worker agents. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentDelegationIntegrationTest {

    private static final String TOKEN = "runtime-token";

    @Autowired private MockMvc mockMvc;
    @Autowired private AgentRepository agentRepository;
    @Autowired private AgentDefinitionVersionRepository definitionRepository;
    @Autowired private PolicyRepository policyRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private ApprovalRequestRepository approvalRequestRepository;
    @Autowired private ExecutionRepository executionRepository;
    @Autowired private NodeExecutionRepository nodeExecutionRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowVersionRepository workflowVersionRepository;
    @Autowired private CacheManager cacheManager;

    @MockitoBean
    private RuntimeClient runtimeClient;

    private final Map<String, Long> ids = new HashMap<>();

    @BeforeEach
    void setUp() {
        cleanUp();
        when(runtimeClient.acceptsToken(TOKEN)).thenReturn(true);
        when(runtimeClient.validateWorkflow(any())).thenReturn(new WorkflowValidation(true, List.of()));
        for (String id : List.of("supervisor", "research", "data", "deep1", "deep2", "deep3", "blank")) {
            ids.put(id, agentRepository.save(new Agent(id, id, ApiKeyGenerator.hash("k"))).getId());
        }
        policyRepository.save(new Policy("AGENT:data", null, RiskLevel.LOW));
        policyRepository.save(new Policy("AGENT:research", null, RiskLevel.LOW));
    }

    @AfterEach
    void cleanUp() {
        nodeExecutionRepository.deleteAll();
        executionRepository.deleteAll();
        workflowVersionRepository.deleteAll();
        workflowRepository.deleteAll();
        auditLogRepository.deleteAll();
        approvalRequestRepository.deleteAll();
        definitionRepository.deleteAll();
        agentRepository.deleteAll();
        policyRepository.deleteAll();
        cacheManager.getCache("policies").clear();
    }

    private ResultActions admin(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(httpBasic("test-admin", "test-password")));
    }

    private ResultActions define(String agent, String delegates) throws Exception {
        return admin(put("/api/v1/agents/{id}/definition", ids.get(agent)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"systemPrompt\":\"x\",\"tools\":[],\"delegates\":%s}".formatted(delegates)));
    }

    private static String to(String agentId, String permission) {
        return "{\"agentId\":\"%s\",\"permission\":\"%s\"}".formatted(agentId, permission);
    }

    @Test
    void savesDelegatesAndRejectsOnesThatCannotWork() throws Exception {
        define("research", "[]").andExpect(status().isCreated());
        define("data", "[]").andExpect(status().isCreated());

        define("supervisor", "[%s,%s]".formatted(to("research", "AUTO"), to("data", "APPROVAL")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.definition.delegates[1].permission").value("APPROVAL"));

        define("supervisor", "[%s]".formatted(to("supervisor", "AUTO")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("delegates: an agent cannot delegate to itself"));
        define("supervisor", "[%s]".formatted(to("ghost", "AUTO")))
                .andExpect(jsonPath("$.message").value("delegates: agent 'ghost' does not exist or has no definition"));
        define("supervisor", "[%s]".formatted(to("blank", "AUTO")))
                .andExpect(jsonPath("$.message").value("delegates: agent 'blank' does not exist or has no definition"));
        define("supervisor", "[%s,%s]".formatted(to("research", "AUTO"), to("research", "BLOCKED")))
                .andExpect(jsonPath("$.message").value("delegates: 'research' is listed more than once"));
    }

    @Test
    void rejectsDelegationCycles() throws Exception {
        define("research", "[]");
        define("supervisor", "[%s]".formatted(to("research", "AUTO"))).andExpect(status().isCreated());

        define("research", "[%s]".formatted(to("supervisor", "AUTO")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("delegates: delegation cycle research > supervisor > research"));
    }

    @Test
    void rejectsChainsDeeperThanTheLimit() throws Exception {
        define("deep3", "[]");
        define("deep2", "[%s]".formatted(to("deep3", "AUTO")));
        define("deep1", "[%s]".formatted(to("deep2", "AUTO")));
        define("research", "[%s]".formatted(to("deep1", "AUTO"))).andExpect(status().isCreated());

        define("supervisor", "[%s]".formatted(to("research", "AUTO")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("delegates: delegation chain supervisor > research > deep1 > deep2 > deep3 is deeper than 3"));
    }

    private ResultActions evaluate(String agentId, String action, String delegatedBy) throws Exception {
        String body = "{\"agentId\":\"%s\",\"action\":\"%s\",\"agentVersion\":1,\"executionId\":\"e1\"%s}".formatted(
                agentId, action, delegatedBy == null ? "" : ",\"delegatedBy\":\"%s\"".formatted(delegatedBy));
        return mockMvc.perform(post("/api/v1/actions").header("X-Runtime-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void delegationWithoutAPolicyIsAllowedByDefault() throws Exception {
        define("deep1", "[]");
        define("supervisor", "[%s]".formatted(to("deep1", "AUTO")));

        evaluate("supervisor", "AGENT:deep1", null)
                .andExpect(jsonPath("$.status").value("ALLOWED"))
                .andExpect(jsonPath("$.riskLevel").value("MEDIUM"));
    }

    @Test
    void delegationIsGovernedByTheSupervisorsPermissions() throws Exception {
        define("research", "[]");
        define("data", "[]");
        define("supervisor", "[%s,%s]".formatted(to("research", "APPROVAL"), to("data", "AUTO")));

        evaluate("supervisor", "AGENT:research", null)
                .andExpect(jsonPath("$.status").value("APPROVAL_REQUIRED"))
                .andExpect(jsonPath("$.basis").value("TOOL_REQUIRES_APPROVAL"));
        evaluate("supervisor", "AGENT:data", null)
                .andExpect(jsonPath("$.status").value("ALLOWED"));
        evaluate("supervisor", "AGENT:deep1", null)
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.basis").value("TOOL_NOT_GRANTED"));
    }

    @Test
    void recordsWhoDelegatedTheWork() throws Exception {
        admin(put("/api/v1/agents/{id}/definition", ids.get("research")).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"systemPrompt":"x","tools":[{"server":"notes","tool":"save_note","permission":"APPROVAL"}]}"""))
                .andExpect(status().isCreated());

        evaluate("research", "MCP:notes:save_note", "supervisor").andExpect(jsonPath("$.approvalId").isNumber());

        admin(get("/api/v1/audit-logs").param("agentId", "research"))
                .andExpect(jsonPath("$[0].delegatedBy").value("supervisor"));
        admin(get("/api/v1/approvals").param("executionId", "e1"))
                .andExpect(jsonPath("$[0].agentId").value("research"))
                .andExpect(jsonPath("$[0].delegatedBy").value("supervisor"));
    }

    @Test
    void executionPinsTheDelegatesToo() throws Exception {
        define("deep1", "[]");
        define("research", "[%s]".formatted(to("deep1", "AUTO")));
        define("supervisor", "[%s]".formatted(to("research", "AUTO")));
        admin(post("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON).content("""
                {"workflowId":"team","dsl":{"nodes":[{"id":"start","type":"START"},
                  {"id":"lead","type":"AGENT","config":{"agentId":"supervisor","prompt":"{task}"}},{"id":"end","type":"END"}],
                 "edges":[{"source":"start","target":"lead"},{"source":"lead","target":"end"}]}}
                """)).andExpect(status().isCreated());

        admin(post("/api/v1/executions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowId\":\"team\",\"task\":\"t\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.agentVersions.supervisor").value(1))
                .andExpect(jsonPath("$.agentVersions.research").value(1))
                .andExpect(jsonPath("$.agentVersions.deep1").value(1));

        ArgumentCaptor<JsonNode> dsl = ArgumentCaptor.forClass(JsonNode.class);
        verify(runtimeClient).startExecution(anyString(), eq("t"), dsl.capture());
        JsonNode agents = dsl.getValue().get("agents");
        assertThat(agents.get("supervisor").get("delegates").get(0).get("agentId").asString()).isEqualTo("research");
        assertThat(agents.has("research")).isTrue();
        assertThat(agents.has("deep1")).isTrue();
    }
}
