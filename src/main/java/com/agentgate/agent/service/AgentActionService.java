package com.agentgate.agent.service;

import com.agentgate.agent.dto.ActionRequest;
import com.agentgate.agent.dto.ActionResponse;
import com.agentgate.agent.repository.AgentRepository;
import com.agentgate.approval.domain.ApprovalRequest;
import com.agentgate.approval.service.ApprovalService;
import com.agentgate.common.exception.AgentNotFoundException;
import com.agentgate.risk.ActionStatus;
import com.agentgate.risk.RiskEvaluationResult;
import com.agentgate.risk.RiskEvaluationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentActionService {

    private final AgentRepository agentRepository;
    private final RiskEvaluationService riskEvaluationService;
    private final ApprovalService approvalService;

    public ActionResponse evaluate(ActionRequest request) {
        agentRepository.findByAgentId(request.agentId())
                .orElseThrow(() -> new AgentNotFoundException(request.agentId()));

        RiskEvaluationResult result = riskEvaluationService.evaluate(request.action(), request.labels());

        Long approvalId = null;
        if (result.status() == ActionStatus.APPROVAL_REQUIRED) {
            ApprovalRequest approvalRequest = approvalService.createRequest(
                    request.agentId(), request.action(), request.target(), request.labels(), result.riskLevel());
            approvalId = approvalRequest.getId();
        }

        return new ActionResponse(result.status(), result.riskLevel(), approvalId);
    }
}
