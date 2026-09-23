package com.agentgate.audit;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentgate.agent.domain.Agent;
import com.agentgate.agent.repository.AgentRepository;
import com.agentgate.audit.repository.AuditLogRepository;
import com.agentgate.common.security.ApiKeyGenerator;
import com.agentgate.policy.domain.Policy;
import com.agentgate.policy.repository.PolicyRepository;
import com.agentgate.risk.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditLogIntegrationTest {

    private static final String API_KEY = "test-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void seed() {
        agentRepository.deleteAll();
        policyRepository.deleteAll();
        auditLogRepository.deleteAll();
        cacheManager.getCache("policies").clear();

        agentRepository.save(new Agent("mail-agent", "Mail Agent", ApiKeyGenerator.hash(API_KEY)));
        policyRepository.save(new Policy(null, "PII", RiskLevel.HIGH));
        policyRepository.save(new Policy("VIEW_DATA", null, RiskLevel.LOW));
    }

    @Test
    void everyActionEvaluationIsLoggedAndQueryable() throws Exception {
        mockMvc.perform(post("/api/v1/actions").header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"agentId":"mail-agent","action":"VIEW_DATA","labels":[]}
                        """))
                .andExpect(status().isOk());

        MvcResult highRiskResult = mockMvc.perform(post("/api/v1/actions").header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"agentId":"mail-agent","action":"SEND_EMAIL","labels":["PII"]}
                        """))
                .andExpect(status().isOk())
                .andReturn();
        long approvalId = objectMapper.readTree(highRiskResult.getResponse().getContentAsString())
                .get("approvalId").asLong();

        mockMvc.perform(get("/api/v1/audit-logs").with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/v1/audit-logs?status=APPROVAL_REQUIRED").with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].approvalId").value(approvalId))
                .andExpect(jsonPath("$[0].riskLevel").value("HIGH"));

        mockMvc.perform(post("/api/v1/approvals/" + approvalId + "/approve")
                        .with(httpBasic("test-admin", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/audit-logs?status=APPROVAL_REQUIRED").with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("APPROVAL_REQUIRED"));
    }

    @Test
    void listWithoutCredentialsIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs"))
                .andExpect(status().isUnauthorized());
    }
}
