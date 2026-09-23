package com.agentgate.policy.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentgate.common.exception.PolicyNotFoundException;
import com.agentgate.policy.dto.PolicyResponse;
import com.agentgate.policy.service.PolicyManagementService;
import com.agentgate.risk.RiskLevel;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PolicyController.class)
@AutoConfigureMockMvc(addFilters = false)
class PolicyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PolicyManagementService policyManagementService;

    @Test
    void returnsBadRequestWhenRiskLevelMissing() throws Exception {
        String body = """
                {"actionType":"VIEW_DATA","label":null}
                """;

        mockMvc.perform(post("/api/v1/policies").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void returnsBadRequestWhenRiskLevelIsInvalid() throws Exception {
        String body = """
                {"actionType":"VIEW_DATA","label":null,"riskLevel":"NOT_A_LEVEL"}
                """;

        mockMvc.perform(post("/api/v1/policies").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void createsPolicyAndReturnsCreated() throws Exception {
        when(policyManagementService.create(any()))
                .thenReturn(new PolicyResponse(1L, "VIEW_DATA", null, RiskLevel.LOW, Instant.now()));
        String body = """
                {"actionType":"VIEW_DATA","label":null,"riskLevel":"LOW"}
                """;

        mockMvc.perform(post("/api/v1/policies").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.riskLevel").value("LOW"));
    }

    @Test
    void returnsNotFoundWhenPolicyMissing() throws Exception {
        when(policyManagementService.get(eq(99L))).thenThrow(new PolicyNotFoundException(99L));

        mockMvc.perform(get("/api/v1/policies/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POLICY_NOT_FOUND"));
    }

    @Test
    void deletesPolicyAndReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/policies/1"))
                .andExpect(status().isNoContent());
    }
}
