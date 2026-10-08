package com.agentgate.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentgate.agent.domain.Agent;
import com.agentgate.agent.repository.AgentRepository;
import com.agentgate.audit.repository.AuditLogRepository;
import com.agentgate.approval.repository.ApprovalRequestRepository;
import com.agentgate.common.security.ApiKeyGenerator;
import com.agentgate.policy.repository.PolicyRepository;
import com.agentgate.risk.RiskLevel;
import com.agentgate.runtime.McpServerTools;
import com.agentgate.runtime.McpToolInfo;
import com.agentgate.runtime.RuntimeClient;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ToolRiskIntegrationTest {

    private static final JsonMapper JSON = new JsonMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private ApprovalRequestRepository approvalRequestRepository;

    @Autowired
    private CacheManager cacheManager;

    @MockitoBean
    private RuntimeClient runtimeClient;

    @BeforeEach
    void setUp() {
        cleanUp();
        agentRepository.save(new Agent("notes-agent", "Notes", ApiKeyGenerator.hash("key")));
        when(runtimeClient.listTools(anyBoolean())).thenReturn(List.of(
                new McpServerTools("notes", "url", "agentgate", List.of(
                        tool("list_notes", "{\"readOnlyHint\":true}"),
                        tool("archive_note", "{\"destructiveHint\":false}"),
                        tool("delete_note", null)), null),
                new McpServerTools("files", "stdio", "runtime", List.of(), "connection refused")));
    }

    @AfterEach
    void cleanUp() {
        auditLogRepository.deleteAll();
        approvalRequestRepository.deleteAll();
        agentRepository.deleteAll();
        policyRepository.deleteAll();
        cacheManager.getCache("policies").clear();
    }

    private static McpToolInfo tool(String name, String annotations) {
        return new McpToolInfo(name, null, name + " description", JSON.readTree("{\"type\":\"object\"}"),
                annotations == null ? null : JSON.readTree(annotations));
    }

    private ResultActions admin(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(httpBasic("test-admin", "test-password")));
    }

    private ResultActions setRisk(String tool, String level) throws Exception {
        return admin(put("/api/v1/tool-risks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"server\":\"notes\",\"tool\":\"%s\",\"riskLevel\":\"%s\"}".formatted(tool, level)));
    }

    private ResultActions evaluate(String action) throws Exception {
        return mockMvc.perform(post("/api/v1/actions").header("X-API-Key", "key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentId\":\"notes-agent\",\"action\":\"%s\"}".formatted(action)));
    }

    @Test
    void listsToolsWithDefaultAndSuggestedRisk() throws Exception {
        admin(get("/api/v1/tool-risks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].server").value("notes"))
                .andExpect(jsonPath("$[0].source").value("agentgate"))
                .andExpect(jsonPath("$[0].tools[0].action").value("MCP:notes:list_notes"))
                .andExpect(jsonPath("$[0].tools[0].riskLevel").doesNotExist())
                .andExpect(jsonPath("$[0].tools[0].effectiveRiskLevel").value("HIGH"))
                .andExpect(jsonPath("$[0].tools[0].suggestedRiskLevel").value("LOW"))
                .andExpect(jsonPath("$[0].tools[1].suggestedRiskLevel").value("MEDIUM"))
                .andExpect(jsonPath("$[0].tools[2].suggestedRiskLevel").value("HIGH"))
                .andExpect(jsonPath("$[1].error").value("connection refused"));
    }

    @Test
    void settingARiskChangesHowCallsAreDecided() throws Exception {
        evaluate("MCP:notes:list_notes").andExpect(jsonPath("$.status").value("APPROVAL_REQUIRED"));

        setRisk("list_notes", "LOW").andExpect(status().isOk()).andExpect(jsonPath("$.riskLevel").value("LOW"));
        evaluate("MCP:notes:list_notes").andExpect(jsonPath("$.status").value("ALLOWED"));

        setRisk("list_notes", "BLOCKED").andExpect(status().isOk());
        evaluate("MCP:notes:list_notes").andExpect(jsonPath("$.status").value("BLOCKED"));
        assertThat(policyRepository.findAll()).hasSize(1);  // updated in place

        admin(get("/api/v1/tool-risks"))
                .andExpect(jsonPath("$[0].tools[0].riskLevel").value("BLOCKED"))
                .andExpect(jsonPath("$[0].tools[0].effectiveRiskLevel").value("BLOCKED"))
                .andExpect(jsonPath("$[0].tools[0].policyId").isNumber());
    }

    @Test
    void clearingFallsBackToTheDefault() throws Exception {
        setRisk("list_notes", "LOW");

        admin(delete("/api/v1/tool-risks").param("server", "notes").param("tool", "list_notes"))
                .andExpect(status().isNoContent());

        evaluate("MCP:notes:list_notes").andExpect(jsonPath("$.status").value("APPROVAL_REQUIRED"));
        assertThat(policyRepository.findAll()).isEmpty();
    }

    @Test
    void appliesSuggestionsOnlyWhereNothingIsSet() throws Exception {
        setRisk("delete_note", "BLOCKED");

        admin(post("/api/v1/tool-risks/apply-suggestions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(2));

        admin(get("/api/v1/tool-risks"))
                .andExpect(jsonPath("$[0].tools[0].riskLevel").value("LOW"))
                .andExpect(jsonPath("$[0].tools[1].riskLevel").value("MEDIUM"))
                .andExpect(jsonPath("$[0].tools[2].riskLevel").value("BLOCKED"));
        admin(post("/api/v1/tool-risks/apply-suggestions")).andExpect(jsonPath("$.applied").value(0));
    }

    @Test
    void validatesRequestsAndRequiresAdmin() throws Exception {
        setRisk("bad tool", "LOW").andExpect(status().isBadRequest());
        admin(put("/api/v1/tool-risks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"server\":\"notes\",\"tool\":\"x\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/tool-risks")).andExpect(status().isUnauthorized());
    }

    @Test
    void suggestionsFollowAnnotations() {
        assertThat(ToolRiskService.suggest(null)).isEqualTo(RiskLevel.HIGH);
        assertThat(ToolRiskService.suggest(JSON.readTree("{\"readOnlyHint\":true,\"destructiveHint\":true}")))
                .isEqualTo(RiskLevel.LOW);
        assertThat(ToolRiskService.suggest(JSON.readTree("{\"openWorldHint\":true}"))).isEqualTo(RiskLevel.HIGH);
    }
}
