package com.agentgate.agent;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentgate.agent.domain.Agent;
import com.agentgate.agent.repository.AgentRepository;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentActionIntegrationTest {

    private static final String API_KEY = "test-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void seed() {
        agentRepository.deleteAll();
        policyRepository.deleteAll();
        cacheManager.getCache("policies").clear();

        agentRepository.save(new Agent("mail-agent", "Mail Agent", ApiKeyGenerator.hash(API_KEY)));
        Agent cappedAgent = new Agent("capped-agent", "Capped Agent", ApiKeyGenerator.hash(API_KEY));
        cappedAgent.restrictTo(RiskLevel.LOW);
        agentRepository.save(cappedAgent);
        policyRepository.save(new Policy(null, "PII", RiskLevel.HIGH));
        policyRepository.save(new Policy("VIEW_DATA", null, RiskLevel.LOW));
        policyRepository.save(new Policy("DELETE_DATA", null, RiskLevel.BLOCKED));
    }

    @Test
    void piiLabeledEmailRequiresApproval() throws Exception {
        String body = """
                {"agentId":"mail-agent","action":"SEND_EMAIL","target":"user@test.com","labels":["PII"]}
                """;

        mockMvc.perform(post("/api/v1/actions").header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVAL_REQUIRED"))
                .andExpect(jsonPath("$.riskLevel").value("HIGH"));
    }

    @Test
    void viewDataIsAllowed() throws Exception {
        String body = """
                {"agentId":"mail-agent","action":"VIEW_DATA","labels":[]}
                """;

        mockMvc.perform(post("/api/v1/actions").header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALLOWED"))
                .andExpect(jsonPath("$.riskLevel").value("LOW"));
    }

    @Test
    void deleteDataIsBlocked() throws Exception {
        String body = """
                {"agentId":"mail-agent","action":"DELETE_DATA","labels":[]}
                """;

        mockMvc.perform(post("/api/v1/actions").header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.riskLevel").value("BLOCKED"));
    }

    @Test
    void agentRiskCapOverridesToBlocked() throws Exception {
        String body = """
                {"agentId":"capped-agent","action":"SEND_EMAIL","target":"user@test.com","labels":["PII"]}
                """;

        mockMvc.perform(post("/api/v1/actions").header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.riskLevel").value("BLOCKED"))
                .andExpect(jsonPath("$.approvalId").value(org.hamcrest.CoreMatchers.nullValue()));
    }

    @Test
    void unknownAgentReturnsNotFound() throws Exception {
        String body = """
                {"agentId":"ghost-agent","action":"VIEW_DATA","labels":[]}
                """;

        mockMvc.perform(post("/api/v1/actions").header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AGENT_NOT_FOUND"));
    }

    @Test
    void wrongApiKeyReturnsUnauthorized() throws Exception {
        String body = """
                {"agentId":"mail-agent","action":"VIEW_DATA","labels":[]}
                """;

        mockMvc.perform(post("/api/v1/actions").header("X-API-Key", "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_API_KEY"));
    }

    @Test
    void missingApiKeyReturnsUnauthorized() throws Exception {
        String body = """
                {"agentId":"mail-agent","action":"VIEW_DATA","labels":[]}
                """;

        mockMvc.perform(post("/api/v1/actions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_API_KEY"));
    }
}
