package com.agentgate.agent.service;

import com.agentgate.agent.domain.Agent;
import com.agentgate.agent.domain.ToolPermission;
import com.agentgate.agent.dto.ActionRequest;
import com.agentgate.agent.dto.ActionResponse;
import com.agentgate.agent.dto.AgentDefinition;
import com.agentgate.agent.repository.AgentRepository;
import com.agentgate.approval.domain.ApprovalRequest;
import com.agentgate.approval.service.ApprovalService;
import com.agentgate.audit.service.AuditLogService;
import com.agentgate.common.exception.AgentNotFoundException;
import com.agentgate.common.exception.InvalidApiKeyException;
import com.agentgate.common.security.ApiKeyGenerator;
import com.agentgate.risk.ActionStatus;
import com.agentgate.risk.DecisionBasis;
import com.agentgate.risk.RiskEvaluationResult;
import com.agentgate.risk.RiskEvaluationService;
import com.agentgate.risk.RiskLevel;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Decides an agent action: Tool permission → Policy → Risk → agent cap, then
 * records it and opens an approval request when one is needed.
 */
@Service
@RequiredArgsConstructor
public class AgentActionService {

    private final AgentRepository agentRepository;
    private final AgentDefinitionService agentDefinitionService;
    private final RiskEvaluationService riskEvaluationService;
    private final ApprovalService approvalService;
    private final AuditLogService auditLogService;

    /** For an agent calling with its own API key. */
    public ActionResponse evaluate(ActionRequest request, String apiKey) {
        return evaluate(request, apiKey, false);
    }

    /**
     * @param trustedCaller the runtime (authenticated with the runtime token), which may act for any agent
     */
    public ActionResponse evaluate(ActionRequest request, String apiKey, boolean trustedCaller) {
        Agent agent = agentRepository.findByAgentId(request.agentId())
                .orElseThrow(() -> new AgentNotFoundException(request.agentId()));

        if (!trustedCaller && (apiKey == null || !ApiKeyGenerator.hash(apiKey).equals(agent.getApiKeyHash()))) {
            throw new InvalidApiKeyException();
        }

        Optional<Grant> tool = (request.agentVersion() == null) ? Optional.empty()
                : findGrant(agent, request.agentVersion(), request.action());
        List<String> labels = tool.map(t -> merge(request.labels(), t.labels())).orElse(request.labels());
        Decision decision = decide(agent, request, tool, labels);

        Long approvalId = null;
        if (decision.status() == ActionStatus.APPROVAL_REQUIRED) {
            ApprovalRequest approvalRequest = approvalService.createRequest(
                    request.agentId(), request.action(), request.target(), labels, decision.riskLevel(),
                    request.executionId(), request.reason(), request.delegatedBy());
            approvalId = approvalRequest.getId();
        }

        auditLogService.record(request.agentId(), request.action(), request.target(), labels,
                decision.riskLevel(), decision.status(), approvalId, decision.basis(), request.delegatedBy());

        return new ActionResponse(decision.status(), decision.riskLevel(), approvalId, decision.basis());
    }

    private Decision decide(Agent agent, ActionRequest request, Optional<Grant> tool,
                            List<String> labels) {
        if (request.agentVersion() != null) {
            if (tool.isEmpty()) {
                return Decision.blocked(DecisionBasis.TOOL_NOT_GRANTED);
            }
            if (tool.get().permission() == ToolPermission.BLOCKED) {
                return Decision.blocked(DecisionBasis.TOOL_BLOCKED);
            }
        }

        RiskEvaluationResult policy = riskEvaluationService.evaluate(request.action(), labels);
        RiskLevel cap = agent.getMaxRiskLevel();
        if (cap != null && policy.riskLevel().ordinal() > cap.ordinal()) {
            return Decision.blocked(DecisionBasis.AGENT_RISK_CAP);
        }
        if (policy.status() == ActionStatus.ALLOWED) {
            if (tool.isPresent() && tool.get().permission() == ToolPermission.APPROVAL) {
                return new Decision(policy.riskLevel(), ActionStatus.APPROVAL_REQUIRED, DecisionBasis.TOOL_REQUIRES_APPROVAL);
            }
            if (request.approvalRequested()) {
                return new Decision(policy.riskLevel(), ActionStatus.APPROVAL_REQUIRED, DecisionBasis.APPROVAL_REQUESTED);
            }
        }
        return new Decision(policy.riskLevel(), policy.status(), DecisionBasis.POLICY);
    }

    /** What the definition allows for the action: one of its tools, or a delegation to another agent. */
    private Optional<Grant> findGrant(Agent agent, int version, String action) {
        AgentDefinition definition = agentDefinitionService.definition(agent, version);
        Optional<Grant> tool = definition.tools().stream()
                .filter(t -> t.action().equals(action))
                .findFirst()
                .map(t -> new Grant(t.permission(), t.labels() == null ? List.of() : t.labels()));
        if (tool.isPresent() || definition.delegates() == null) {
            return tool;
        }
        return definition.delegates().stream()
                .filter(d -> d.action().equals(action))
                .findFirst()
                .map(d -> new Grant(d.permission(), List.of()));
    }

    private record Grant(ToolPermission permission, List<String> labels) {
    }

    private static List<String> merge(List<String> requested, List<String> fromTool) {
        Set<String> merged = new LinkedHashSet<>(requested);
        merged.addAll(fromTool);
        return List.copyOf(merged);
    }

    private record Decision(RiskLevel riskLevel, ActionStatus status, DecisionBasis basis) {

        static Decision blocked(DecisionBasis basis) {
            return new Decision(RiskLevel.BLOCKED, ActionStatus.BLOCKED, basis);
        }
    }
}
