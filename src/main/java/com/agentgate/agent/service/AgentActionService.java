package com.agentgate.agent.service;

import com.agentgate.agent.dto.ActionRequest;
import com.agentgate.agent.dto.ActionResponse;
import com.agentgate.agent.repository.AgentRepository;
import com.agentgate.common.exception.AgentNotFoundException;
import com.agentgate.risk.RiskEvaluationResult;
import com.agentgate.risk.RiskEvaluationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentActionService {

    private final AgentRepository agentRepository;
    private final RiskEvaluationService riskEvaluationService;

    public ActionResponse evaluate(ActionRequest request) {
        agentRepository.findByAgentId(request.agentId())
                .orElseThrow(() -> new AgentNotFoundException(request.agentId()));

        RiskEvaluationResult result = riskEvaluationService.evaluate(request.action(), request.labels());
        return new ActionResponse(result.status(), result.riskLevel());
    }
}
