package com.agentgate.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.agentgate.agent.domain.Agent;
import com.agentgate.agent.dto.ActionRequest;
import com.agentgate.agent.dto.ActionResponse;
import com.agentgate.agent.repository.AgentRepository;
import com.agentgate.common.exception.AgentNotFoundException;
import com.agentgate.risk.ActionStatus;
import com.agentgate.risk.RiskEvaluationResult;
import com.agentgate.risk.RiskEvaluationService;
import com.agentgate.risk.RiskLevel;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentActionServiceTest {

    @Mock
    private AgentRepository agentRepository;

    @Mock
    private RiskEvaluationService riskEvaluationService;

    @Test
    void throwsWhenAgentIsUnknown() {
        when(agentRepository.findByAgentId("ghost-agent")).thenReturn(Optional.empty());
        AgentActionService service = new AgentActionService(agentRepository, riskEvaluationService);

        ActionRequest request = new ActionRequest("ghost-agent", "VIEW_DATA", null, List.of());

        assertThatThrownBy(() -> service.evaluate(request))
                .isInstanceOf(AgentNotFoundException.class);
    }

    @Test
    void returnsEvaluationResultWhenAgentExists() {
        when(agentRepository.findByAgentId("mail-agent"))
                .thenReturn(Optional.of(new Agent("mail-agent", "Mail Agent")));
        when(riskEvaluationService.evaluate("VIEW_DATA", List.of()))
                .thenReturn(new RiskEvaluationResult(RiskLevel.LOW, ActionStatus.ALLOWED));
        AgentActionService service = new AgentActionService(agentRepository, riskEvaluationService);

        ActionRequest request = new ActionRequest("mail-agent", "VIEW_DATA", null, List.of());

        ActionResponse response = service.evaluate(request);

        assertThat(response.status()).isEqualTo(ActionStatus.ALLOWED);
        assertThat(response.riskLevel()).isEqualTo(RiskLevel.LOW);
    }
}
