package com.agentgate.agent;

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
import com.agentgate.policy.domain.Policy;
import com.agentgate.policy.repository.PolicyRepository;
import com.agentgate.risk.RiskLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {
        "agentgate.runtime.base-url=http://localhost:1",
        "agentgate.runtime.token=runtime-secret"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentToolPermissionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private AgentDefinitionVersionRepository definitionRepository;

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private ApprovalRequestRepository approvalRequestRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        Long id = agentRepository.save(new Agent("note-agent", "Note Agent", ApiKeyGenerator.hash("agent-key"))).getId();
        policyRepository.save(new Policy("MCP:notes:list_notes", null, RiskLevel.LOW));
        policyRepository.save(new Policy("MCP:notes:save_note", null, RiskLevel.LOW));
        mockMvc.perform(put("/api/v1/agents/{id}/definition", id)
                        .with(httpBasic("test-admin", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"systemPrompt":"x","tools":[
                                  {"server":"notes","tool":"list_notes","permission":"AUTO"},
                                  {"server":"notes","tool":"save_note","permission":"APPROVAL"},
                                  {"server":"notes","tool":"wipe","permission":"BLOCKED"}]}"""))
                .andExpect(status().isCreated());
    }

    @AfterEach
    void cleanUp() {
        auditLogRepository.deleteAll();
        approvalRequestRepository.deleteAll();
        definitionRepository.deleteAll();
        agentRepository.deleteAll();
        policyRepository.deleteAll();
        cacheManager.getCache("policies").clear();
    }

    private ResultActions evaluate(String action, String header, String value) throws Exception {
        String body = """
                {"agentId":"note-agent","action":"%s","agentVersion":1,"executionId":"exec-1"}
                """.formatted(action);
        return mockMvc.perform(post("/api/v1/actions").header(header, value)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void runtimeTokenEvaluatesOnBehalfOfTheAgent() throws Exception {
        evaluate("MCP:notes:list_notes", "X-Runtime-Token", "runtime-secret")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALLOWED"))
                .andExpect(jsonPath("$.basis").value("POLICY"));

        mockMvc.perform(get("/api/v1/audit-logs").param("agentId", "note-agent")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(jsonPath("$[0].agentId").value("note-agent"))
                .andExpect(jsonPath("$[0].basis").value("POLICY"));
    }

    @Test
    void appliesTheDefinitionsToolPermissions() throws Exception {
        evaluate("MCP:notes:save_note", "X-Runtime-Token", "runtime-secret")
                .andExpect(jsonPath("$.status").value("APPROVAL_REQUIRED"))
                .andExpect(jsonPath("$.basis").value("TOOL_REQUIRES_APPROVAL"))
                .andExpect(jsonPath("$.approvalId").isNumber());
        evaluate("MCP:notes:wipe", "X-Runtime-Token", "runtime-secret")
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.basis").value("TOOL_BLOCKED"));
        evaluate("MCP:files:read", "X-Runtime-Token", "runtime-secret")
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.basis").value("TOOL_NOT_GRANTED"));
    }

    @Test
    void agentsOwnApiKeyStillWorks() throws Exception {
        evaluate("MCP:notes:list_notes", "X-API-Key", "agent-key")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALLOWED"));
    }

    @Test
    void rejectsAWrongRuntimeToken() throws Exception {
        evaluate("MCP:notes:list_notes", "X-Runtime-Token", "guess")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_API_KEY"));
    }

    @Test
    void returnsNotFoundForAnUnknownDefinitionVersion() throws Exception {
        mockMvc.perform(post("/api/v1/actions").header("X-Runtime-Token", "runtime-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"agentId":"note-agent","action":"MCP:notes:list_notes","agentVersion":9}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AGENT_DEFINITION_NOT_FOUND"));
    }
}
