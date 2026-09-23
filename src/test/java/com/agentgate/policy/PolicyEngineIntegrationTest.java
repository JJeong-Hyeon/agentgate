package com.agentgate.policy;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentgate.agent.domain.Agent;
import com.agentgate.agent.repository.AgentRepository;
import com.agentgate.common.security.ApiKeyGenerator;
import com.agentgate.policy.domain.Policy;
import com.agentgate.policy.repository.PolicyRepository;
import com.agentgate.risk.RiskLevel;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PolicyEngineIntegrationTest {

    private static final String API_KEY = "test-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void seed() {
        agentRepository.deleteAll();
        policyRepository.deleteAll();
        cacheManager.getCache("policies").clear();
        agentRepository.save(new Agent("mail-agent", "Mail Agent", ApiKeyGenerator.hash(API_KEY)));
    }

    @Test
    void policyCreatedViaApiImmediatelyAffectsActionEvaluation() throws Exception {
        String createBody = """
                {"actionType":"EXPORT_DATA","label":null,"riskLevel":"MEDIUM"}
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/policies")
                        .with(httpBasic("test-admin", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated())
                .andReturn();
        long policyId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asLong();

        String actionBody = """
                {"agentId":"mail-agent","action":"EXPORT_DATA","labels":[]}
                """;
        mockMvc.perform(post("/api/v1/actions").header("X-API-Key", API_KEY).contentType(MediaType.APPLICATION_JSON).content(actionBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALLOWED"))
                .andExpect(jsonPath("$.riskLevel").value("MEDIUM"));

        mockMvc.perform(delete("/api/v1/policies/" + policyId)
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/actions").header("X-API-Key", API_KEY).contentType(MediaType.APPLICATION_JSON).content(actionBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVAL_REQUIRED"))
                .andExpect(jsonPath("$.riskLevel").value("HIGH"));
    }

    @Test
    void policyLookupIsCachedAndEvictedOnWrite() throws Exception {
        // 1. Create via the API — this evicts any stale cache from other tests, so the cache
        //    starts from a known-correct state regardless of shared test context pollution.
        String createBody = """
                {"actionType":"CACHE_TEST","label":null,"riskLevel":"LOW"}
                """;
        MvcResult createResult = mockMvc.perform(post("/api/v1/policies")
                        .with(httpBasic("test-admin", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated())
                .andReturn();
        long policyId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asLong();

        String actionBody = """
                {"agentId":"mail-agent","action":"CACHE_TEST","labels":[]}
                """;

        // 2. First evaluation populates the cache.
        mockMvc.perform(post("/api/v1/actions").header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskLevel").value("LOW"));

        // 3. Mutate directly, bypassing PolicyManagementService — no cache eviction happens.
        Policy policy = policyRepository.findById(policyId).orElseThrow();
        policy.update("CACHE_TEST", null, RiskLevel.HIGH, null);
        policyRepository.save(policy);

        // 4. Still LOW: proves the evaluation is served from the cache, not a fresh DB read.
        mockMvc.perform(post("/api/v1/actions").header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskLevel").value("LOW"));

        // 5. Update through the real API — this evicts the cache.
        String updateBody = """
                {"actionType":"CACHE_TEST","label":null,"riskLevel":"HIGH"}
                """;
        mockMvc.perform(put("/api/v1/policies/" + policyId)
                        .with(httpBasic("test-admin", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isOk());

        // 6. Now HIGH: proves eviction correctly forces a fresh read.
        mockMvc.perform(post("/api/v1/actions").header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskLevel").value("HIGH"));
    }
}
