package com.agentgate.agent.service;

import com.agentgate.agent.dto.ActionRequest;
import com.agentgate.agent.dto.ActionResponse;
import com.agentgate.agent.repository.AgentRepository;
import com.agentgate.approval.domain.ApprovalRequest;
import com.agentgate.approval.service.ApprovalService;
import com.agentgate.agent.domain.Agent;
import com.agentgate.audit.service.AuditLogService;
import com.agentgate.common.exception.AgentNotFoundException;
import com.agentgate.common.exception.InvalidApiKeyException;
import com.agentgate.common.security.ApiKeyGenerator;
import com.agentgate.risk.ActionStatus;
import com.agentgate.risk.RiskEvaluationResult;
import com.agentgate.risk.RiskEvaluationService;
import com.agentgate.risk.RiskLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentActionService {

    private final AgentRepository agentRepository;
    private final RiskEvaluationService riskEvaluationService;
    private final ApprovalService approvalService;
    private final AuditLogService auditLogService;

    public ActionResponse evaluate(ActionRequest request, String apiKey) {
        Agent agent = agentRepository.findByAgentId(request.agentId())
                .orElseThrow(() -> new AgentNotFoundException(request.agentId()));

        if (apiKey == null || !ApiKeyGenerator.hash(apiKey).equals(agent.getApiKeyHash())) {
            throw new InvalidApiKeyException();
        }

        RiskEvaluationResult result = applyAgentCap(agent, riskEvaluationService.evaluate(request.action(), request.labels()));

        Long approvalId = null;
        if (result.status() == ActionStatus.APPROVAL_REQUIRED) {
            ApprovalRequest approvalRequest = approvalService.createRequest(
                    request.agentId(), request.action(), request.target(), request.labels(), result.riskLevel());
            approvalId = approvalRequest.getId();
        }

        auditLogService.record(request.agentId(), request.action(), request.target(), request.labels(),
                result.riskLevel(), result.status(), approvalId);

        return new ActionResponse(result.status(), result.riskLevel(), approvalId);
    }

    private RiskEvaluationResult applyAgentCap(Agent agent, RiskEvaluationResult result) {
        RiskLevel cap = agent.getMaxRiskLevel();
        if (cap != null && result.riskLevel().ordinal() > cap.ordinal()) {
            return new RiskEvaluationResult(RiskLevel.BLOCKED, ActionStatus.BLOCKED);
        }
        return result;
    }
}
