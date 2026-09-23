package com.agentgate.approval;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentgate.agent.domain.Agent;
import com.agentgate.agent.repository.AgentRepository;
import com.agentgate.approval.repository.ApprovalRequestRepository;
import com.agentgate.common.security.ApiKeyGenerator;
import com.agentgate.policy.domain.Policy;
import com.agentgate.policy.repository.PolicyRepository;
import com.agentgate.risk.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
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

    @BeforeEach
    void seed() {
        agentRepository.deleteAll();
        policyRepository.deleteAll();
        approvalRequestRepository.deleteAll();

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
                .andExpect(jsonPath("$.decidedBy").value("alice"));
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
}
