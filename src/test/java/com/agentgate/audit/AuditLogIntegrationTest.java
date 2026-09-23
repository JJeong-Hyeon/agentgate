package com.agentgate.audit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentgate.agent.domain.Agent;
import com.agentgate.agent.repository.AgentRepository;
import com.agentgate.audit.repository.AuditLogRepository;
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
class AuditLogIntegrationTest {

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

    @BeforeEach
    void seed() {
        agentRepository.deleteAll();
        policyRepository.deleteAll();
        auditLogRepository.deleteAll();

        agentRepository.save(new Agent("mail-agent", "Mail Agent"));
        policyRepository.save(new Policy(null, "PII", RiskLevel.HIGH));
        policyRepository.save(new Policy("VIEW_DATA", null, RiskLevel.LOW));
    }

    @Test
    void everyActionEvaluationIsLoggedAndQueryable() throws Exception {
        mockMvc.perform(post("/api/v1/actions").contentType(MediaType.APPLICATION_JSON).content("""
                        {"agentId":"mail-agent","action":"VIEW_DATA","labels":[]}
                        """))
                .andExpect(status().isOk());

        MvcResult highRiskResult = mockMvc.perform(post("/api/v1/actions").contentType(MediaType.APPLICATION_JSON).content("""
                        {"agentId":"mail-agent","action":"SEND_EMAIL","labels":["PII"]}
                        """))
                .andExpect(status().isOk())
                .andReturn();
        long approvalId = objectMapper.readTree(highRiskResult.getResponse().getContentAsString())
                .get("approvalId").asLong();

        mockMvc.perform(get("/api/v1/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/v1/audit-logs?status=APPROVAL_REQUIRED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].approvalId").value(approvalId))
                .andExpect(jsonPath("$[0].riskLevel").value("HIGH"));

        mockMvc.perform(post("/api/v1/approvals/" + approvalId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/audit-logs?status=APPROVAL_REQUIRED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("APPROVAL_REQUIRED"));
    }
}
