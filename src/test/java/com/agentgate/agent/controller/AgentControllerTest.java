package com.agentgate.agent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentgate.agent.dto.AgentCreateResponse;
import com.agentgate.agent.dto.AgentResponse;
import com.agentgate.agent.service.AgentManagementService;
import com.agentgate.risk.RiskLevel;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AgentController.class)
@AutoConfigureMockMvc(addFilters = false)
class AgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AgentManagementService agentManagementService;

    @Test
    void createReturnsCreatedWithApiKey() throws Exception {
        when(agentManagementService.create(any()))
                .thenReturn(new AgentCreateResponse(1L, "new-agent", "New Agent", "plain-text-key", Instant.now()));
        String body = """
                {"agentId":"new-agent","name":"New Agent"}
                """;

        mockMvc.perform(post("/api/v1/agents").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.apiKey").value("plain-text-key"));
    }

    @Test
    void returnsBadRequestWhenAgentIdIsBlank() throws Exception {
        String body = """
                {"agentId":"","name":"New Agent"}
                """;

        mockMvc.perform(post("/api/v1/agents").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void restrictReturnsUpdatedAgent() throws Exception {
        when(agentManagementService.restrict(eq(1L), eq(RiskLevel.MEDIUM)))
                .thenReturn(new AgentResponse(1L, "mail-agent", "Mail Agent", null, RiskLevel.MEDIUM, 0, Instant.now()));
        String body = """
                {"maxRiskLevel":"MEDIUM"}
                """;

        mockMvc.perform(put("/api/v1/agents/1/max-risk-level").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxRiskLevel").value("MEDIUM"));
    }
}
