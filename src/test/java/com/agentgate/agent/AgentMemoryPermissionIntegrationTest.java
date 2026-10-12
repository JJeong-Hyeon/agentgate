package com.agentgate.agent;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
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

/** An agent definition's memory permissions, applied to MEMORY:SEARCH and MEMORY:SAVE. */
@SpringBootTest(properties = {
        "agentgate.runtime.base-url=http://localhost:1",
        "agentgate.runtime.token=runtime-secret"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentMemoryPermissionIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AgentRepository agentRepository;
    @Autowired private AgentDefinitionVersionRepository definitionRepository;
    @Autowired private PolicyRepository policyRepository;
    @Autowired private ApprovalRequestRepository approvalRequestRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private CacheManager cacheManager;

    private Long id;

    @BeforeEach
    void setUp() {
        cleanUp();
        id = agentRepository.save(new Agent("memo-agent", "Memo", ApiKeyGenerator.hash("k"))).getId();
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

    private ResultActions define(String memory) throws Exception {
        return mockMvc.perform(put("/api/v1/agents/{id}/definition", id)
                .with(httpBasic("test-admin", "test-password"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"systemPrompt\":\"x\",\"tools\":[]%s}".formatted(memory == null ? "" : ",\"memory\":" + memory)));
    }

    private ResultActions evaluate(String action) throws Exception {
        return mockMvc.perform(post("/api/v1/actions").header("X-Runtime-Token", "runtime-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentId\":\"memo-agent\",\"action\":\"%s\",\"agentVersion\":1}".formatted(action)));
    }

    @Test
    void appliesReadAndWritePermissions() throws Exception {
        define("{\"read\":\"AUTO\",\"write\":\"APPROVAL\",\"recall\":3}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.definition.memory.recall").value(3));

        evaluate("MEMORY:SEARCH")
                .andExpect(jsonPath("$.status").value("ALLOWED"))
                .andExpect(jsonPath("$.riskLevel").value("MEDIUM"));
        evaluate("MEMORY:SAVE")
                .andExpect(jsonPath("$.status").value("APPROVAL_REQUIRED"))
                .andExpect(jsonPath("$.basis").value("TOOL_REQUIRES_APPROVAL"));
    }

    @Test
    void policiesStillApply() throws Exception {
        policyRepository.save(new Policy("MEMORY:SAVE", null, RiskLevel.BLOCKED));
        define("{\"read\":\"AUTO\",\"write\":\"AUTO\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.definition.memory.recall").value(0));

        evaluate("MEMORY:SAVE").andExpect(jsonPath("$.status").value("BLOCKED"));
    }

    @Test
    void withoutMemoryTheActionsAreNotGranted() throws Exception {
        define(null).andExpect(status().isCreated());

        evaluate("MEMORY:SEARCH")
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.basis").value("TOOL_NOT_GRANTED"));
    }

    @Test
    void recallNeedsAutomaticRead() throws Exception {
        define("{\"read\":\"APPROVAL\",\"write\":\"BLOCKED\",\"recall\":2}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("recall needs read AUTO")));
        define("{\"read\":\"AUTO\",\"write\":\"AUTO\",\"recall\":11}").andExpect(status().isBadRequest());
    }
}
