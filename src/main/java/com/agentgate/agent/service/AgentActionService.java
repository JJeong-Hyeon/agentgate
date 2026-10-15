package com.agentgate.agent.service;

import com.agentgate.agent.domain.Agent;
import com.agentgate.agent.domain.ToolPermission;
import com.agentgate.agent.dto.ActionRequest;
import com.agentgate.agent.dto.ActionResponse;
import com.agentgate.agent.dto.AgentDefinition;
import com.agentgate.agent.repository.AgentRepository;
import com.agentgate.approval.domain.ApprovalPayload;
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

    /** Actions the runtime evaluates before an agent uses a non-default LLM provider: "LLM:gemini". */
    private static final String MODEL_ACTION_PREFIX = "LLM:";

    /** For an agent calling with its own API key. */
    public ActionResponse evaluate(ActionRequest request, String apiKey) {
        return evaluate(request, apiKey, false);
    }

    /**
     * @param trustedCaller the runtime (authenticated with the runtime token), which may act for any agent
     */
    public ActionResponse evaluate(ActionRequest request, String apiKey, boolean trustedCaller) {
        Optional<Agent> found = agentRepository.findByAgentId(request.agentId());
        if (!trustedCaller) {
            // An unknown agent looks like a wrong key, so callers cannot probe which agent ids exist.
            String hash = apiKey == null ? null : ApiKeyGenerator.hash(apiKey);
            if (found.isEmpty() || hash == null || !hash.equals(found.get().getApiKeyHash())) {
                throw new InvalidApiKeyException();
            }
        }
        Agent agent = found.orElseThrow(() -> new AgentNotFoundException(request.agentId()));

        // A caller may omit the version (e.g. a direct Action API call). If the agent has never had a
        // definition, there is nothing to enforce: it only ever used the basic policy/risk flow. If it
        // does have one, falling back to the latest version keeps the tool grant check from being
        // silently skipped just because the caller didn't pass a version.
        Integer resolvedVersion = request.agentVersion() != null ? request.agentVersion()
                : (agent.getLatestDefinitionVersion() > 0 ? agent.getLatestDefinitionVersion() : null);
        // Using a model provider is not a tool an agent definition grants; policies decide it.
        boolean enforceGrant = resolvedVersion != null && !request.action().startsWith(MODEL_ACTION_PREFIX);
        Optional<Grant> tool = enforceGrant
                ? findGrant(agent, resolvedVersion, request.action()) : Optional.empty();
        List<String> labels = tool.map(t -> merge(request.labels(), t.labels())).orElse(request.labels());
        Decision decision = decide(agent, request, tool, labels, enforceGrant);

        Long approvalId = null;
        if (decision.status() == ActionStatus.APPROVAL_REQUIRED) {
            ApprovalRequest approvalRequest = approvalService.createRequest(
                    request.agentId(), request.action(), request.target(), labels, decision.riskLevel(),
                    request.executionId(), request.reason(), request.delegatedBy(),
                    new ApprovalPayload(request.payloadHash(), request.payloadPreview()));
            approvalId = approvalRequest.getId();
        }

        auditLogService.record(request.agentId(), request.action(), request.target(), labels,
                decision.riskLevel(), decision.status(), approvalId, decision.basis(), request.delegatedBy(),
                request.executionId());

        return new ActionResponse(decision.status(), decision.riskLevel(), approvalId, decision.basis());
    }

    private Decision decide(Agent agent, ActionRequest request, Optional<Grant> tool,
                            List<String> labels, boolean enforceGrant) {
        if (enforceGrant) {
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

    /** What the definition allows for the action: one of its tools, a delegation to another agent, or memory. */
    private Optional<Grant> findGrant(Agent agent, int version, String action) {
        AgentDefinition definition = agentDefinitionService.definition(agent, version);
        Optional<Grant> tool = definition.tools().stream()
                .filter(t -> t.action().equals(action))
                .findFirst()
                .map(t -> new Grant(t.permission(), t.labels() == null ? List.of() : t.labels()));
        if (tool.isPresent()) {
            return tool;
        }
        if (definition.memory() != null && definition.memory().permissionFor(action) != null) {
            return Optional.of(new Grant(definition.memory().permissionFor(action), List.of()));
        }
        if (definition.delegates() == null) {
            return Optional.empty();
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
