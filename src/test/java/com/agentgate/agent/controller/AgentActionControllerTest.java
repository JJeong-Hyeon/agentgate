package com.agentgate.agent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentgate.agent.dto.ActionResponse;
import com.agentgate.agent.service.AgentActionService;
import com.agentgate.common.exception.AgentNotFoundException;
import com.agentgate.risk.ActionStatus;
import com.agentgate.risk.DecisionBasis;
import com.agentgate.runtime.RuntimeClient;
import com.agentgate.risk.RiskLevel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AgentActionController.class)
@AutoConfigureMockMvc(addFilters = false)
class AgentActionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RuntimeClient runtimeClient;

    @MockitoBean
    private AgentActionService agentActionService;

    @Test
    void returnsBadRequestWhenAgentIdIsBlank() throws Exception {
        String body = """
                {"agentId":"","action":"VIEW_DATA","labels":[]}
                """;

        mockMvc.perform(post("/api/v1/actions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void returnsUnauthorizedWhenApiKeyHeaderIsMissing() throws Exception {
        String body = """
                {"agentId":"mail-agent","action":"VIEW_DATA","labels":[]}
                """;

        mockMvc.perform(post("/api/v1/actions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_API_KEY"));
    }

    @Test
    void returnsNotFoundWhenAgentIsUnknown() throws Exception {
        when(agentActionService.evaluate(any(), any(), anyBoolean()))
                .thenThrow(new AgentNotFoundException("ghost-agent"));
        String body = """
                {"agentId":"ghost-agent","action":"VIEW_DATA","labels":[]}
                """;

        mockMvc.perform(post("/api/v1/actions").header("X-API-Key", "test-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AGENT_NOT_FOUND"));
    }

    @Test
    void returnsEvaluationResultWhenRequestIsValid() throws Exception {
        when(agentActionService.evaluate(any(), any(), anyBoolean()))
                .thenReturn(new ActionResponse(ActionStatus.ALLOWED, RiskLevel.LOW, null, DecisionBasis.POLICY));
        String body = """
                {"agentId":"mail-agent","action":"VIEW_DATA","labels":[]}
                """;

        mockMvc.perform(post("/api/v1/actions").header("X-API-Key", "test-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALLOWED"))
                .andExpect(jsonPath("$.riskLevel").value("LOW"));
    }
}
