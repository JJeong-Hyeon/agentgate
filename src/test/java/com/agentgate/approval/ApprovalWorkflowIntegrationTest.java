package com.agentgate.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentgate.agent.domain.Agent;
import com.agentgate.agent.repository.AgentRepository;
import com.agentgate.approval.domain.ApprovalStatus;
import com.agentgate.approval.repository.ApprovalRequestRepository;
import com.agentgate.runtime.RuntimeClient;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApprovalWorkflowIntegrationTest {

    private static final String API_KEY = "test-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private ApprovalRequestRepository approvalRequestRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CacheManager cacheManager;

    @MockitoBean
    private RuntimeClient runtimeClient;

    @BeforeEach
    void seed() {
        agentRepository.deleteAll();
        policyRepository.deleteAll();
        approvalRequestRepository.deleteAll();
        cacheManager.getCache("policies").clear();

        agentRepository.save(new Agent("mail-agent", "Mail Agent", ApiKeyGenerator.hash(API_KEY)));
        policyRepository.save(new Policy(null, "PII", RiskLevel.HIGH));
    }

    @Test
    void highRiskActionCreatesApprovalRequestThatCanBeApproved() throws Exception {
        String actionBody = """
                {"agentId":"mail-agent","action":"SEND_EMAIL","target":"user@test.com","labels":["PII"]}
                """;

        MvcResult actionResult = mockMvc.perform(post("/api/v1/actions").header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVAL_REQUIRED"))
                .andExpect(jsonPath("$.approvalId").exists())
                .andReturn();
        long approvalId = objectMapper.readTree(actionResult.getResponse().getContentAsString())
                .get("approvalId").asLong();

        mockMvc.perform(get("/api/v1/approvals/" + approvalId).with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(post("/api/v1/approvals/" + approvalId + "/approve")
                        .with(httpBasic("test-admin", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"decidedBy":"alice"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(get("/api/v1/approvals/" + approvalId).with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                // The signed-in user, not the decidedBy the request body claims.
                .andExpect(jsonPath("$.decidedBy").value("test-admin"));
    }

    @Test
    void approvingTwiceReturnsConflict() throws Exception {
        String actionBody = """
                {"agentId":"mail-agent","action":"SEND_EMAIL","labels":["PII"]}
                """;
        MvcResult actionResult = mockMvc.perform(post("/api/v1/actions").header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody))
                .andExpect(status().isOk())
                .andReturn();
        long approvalId = objectMapper.readTree(actionResult.getResponse().getContentAsString())
                .get("approvalId").asLong();

        mockMvc.perform(post("/api/v1/approvals/" + approvalId + "/approve")
                        .with(httpBasic("test-admin", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/approvals/" + approvalId + "/approve")
                        .with(httpBasic("test-admin", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_APPROVAL_STATE"));
    }

    @Test
    void approvalRecordsExecutionIdAndCanBeFilteredByIt() throws Exception {
        mockMvc.perform(post("/api/v1/actions").header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"agentId":"mail-agent","action":"SEND_EMAIL","labels":["PII"],"executionId":"exec-1"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/actions").header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"agentId":"mail-agent","action":"SEND_EMAIL","labels":["PII"]}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/approvals").param("executionId", "exec-1")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].executionId").value("exec-1"))
                .andExpect(jsonPath("$[0].status").value("PENDING"));

        mockMvc.perform(get("/api/v1/approvals").param("executionId", "exec-1").param("status", "APPROVED")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void executionIdLongerThan64CharsIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/actions").header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"agentId":"mail-agent","action":"SEND_EMAIL","executionId":"%s"}
                                """.formatted("x".repeat(65))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void decidingAnExecutionApprovalResumesTheRuntime() throws Exception {
        when(runtimeClient.isEnabled()).thenReturn(true);
        when(runtimeClient.resume(any(), any(), any())).thenReturn(true);
        MvcResult actionResult = mockMvc.perform(post("/api/v1/actions").header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"agentId":"mail-agent","action":"SEND_EMAIL","labels":["PII"],"executionId":"exec-1"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long approvalId = objectMapper.readTree(actionResult.getResponse().getContentAsString())
                .get("approvalId").asLong();

        mockMvc.perform(post("/api/v1/approvals/" + approvalId + "/reject")
                        .with(httpBasic("test-admin", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        verify(runtimeClient).resume("exec-1", approvalId, ApprovalStatus.REJECTED);
        assertThat(approvalRequestRepository.findById(approvalId).orElseThrow().getRuntimeNotifiedAt()).isNotNull();
    }

    @Test
    void decidingADirectApprovalDoesNotCallTheRuntime() throws Exception {
        when(runtimeClient.isEnabled()).thenReturn(true);
        MvcResult actionResult = mockMvc.perform(post("/api/v1/actions").header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"agentId":"mail-agent","action":"SEND_EMAIL","labels":["PII"]}
                                """))
                .andReturn();
        long approvalId = objectMapper.readTree(actionResult.getResponse().getContentAsString())
                .get("approvalId").asLong();

        mockMvc.perform(post("/api/v1/approvals/" + approvalId + "/approve")
                        .with(httpBasic("test-admin", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        verify(runtimeClient, never()).resume(any(), any(), any());
    }

    @Test
    void requireApprovalAsksHumanEvenForAllowedActions() throws Exception {
        policyRepository.save(new Policy("VIEW_DATA", null, RiskLevel.LOW));
        cacheManager.getCache("policies").clear();

        MvcResult result = mockMvc.perform(post("/api/v1/actions").header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"agentId":"mail-agent","action":"VIEW_DATA","requireApproval":true,
                                 "reason":"Confirm before reading the report","executionId":"exec-9"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVAL_REQUIRED"))
                .andExpect(jsonPath("$.riskLevel").value("LOW"))
                .andReturn();
        long approvalId = objectMapper.readTree(result.getResponse().getContentAsString()).get("approvalId").asLong();

        mockMvc.perform(get("/api/v1/approvals/" + approvalId).with(httpBasic("test-admin", "test-password")))
                .andExpect(jsonPath("$.reason").value("Confirm before reading the report"))
                .andExpect(jsonPath("$.executionId").value("exec-9"));
    }

    @Test
    void requireApprovalDoesNotOverrideBlockedPolicy() throws Exception {
        policyRepository.save(new Policy("DELETE_DATA", null, RiskLevel.BLOCKED));
        cacheManager.getCache("policies").clear();

        mockMvc.perform(post("/api/v1/actions").header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"agentId":"mail-agent","action":"DELETE_DATA","requireApproval":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.approvalId").doesNotExist());
    }
}
