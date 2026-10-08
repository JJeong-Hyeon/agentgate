package com.agentgate.tool;

import com.agentgate.policy.domain.Policy;
import com.agentgate.policy.dto.PolicyRequest;
import com.agentgate.policy.repository.PolicyRepository;
import com.agentgate.policy.service.PolicyManagementService;
import com.agentgate.risk.RiskEvaluationService;
import com.agentgate.risk.RiskLevel;
import com.agentgate.runtime.McpServerTools;
import com.agentgate.runtime.McpToolInfo;
import com.agentgate.runtime.RuntimeClient;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Per-tool risk levels for MCP tools. A tool's risk is an ordinary policy on its action
 * ({@code MCP:<server>:<tool>}, no label), so evaluation, caching and audit stay the same.
 */
@Service
@RequiredArgsConstructor
public class ToolRiskService {

    private final RuntimeClient runtimeClient;
    private final PolicyRepository policyRepository;
    private final PolicyManagementService policyManagementService;

    public static String action(String server, String tool) {
        return "MCP:%s:%s".formatted(server, tool);
    }

    public List<ToolRisk> list(boolean refresh) {
        List<Policy> policies = policyRepository.findAll();
        return runtimeClient.listTools(refresh).stream()
                .map(server -> new ToolRisk(server.server(), server.source(), server.transport(), server.error(),
                        tools(server, policies)))
                .toList();
    }

    public ToolRisk.Tool set(ToolRiskRequest request) {
        String action = action(request.server(), request.tool());
        PolicyRequest policy = new PolicyRequest(action, null, request.riskLevel(), null);
        Optional<Policy> existing = toolPolicy(action, policyRepository.findAll());
        Long id = existing.isPresent()
                ? policyManagementService.update(existing.get().getId(), policy).id()
                : policyManagementService.create(policy).id();
        return new ToolRisk.Tool(request.tool(), null, null, action, request.riskLevel(), id, request.riskLevel(), null);
    }

    /** Removes the tool's risk level; calls to it fall back to label policies or the default. */
    public void clear(String server, String tool) {
        String action = action(server, tool);
        policyRepository.findAll().stream()
                .filter(p -> isToolPolicy(p, action))
                .map(Policy::getId)
                .toList()
                .forEach(policyManagementService::delete);
    }

    /** Sets the suggested level on every listed tool that has none; returns how many were set. */
    public int applySuggestions() {
        List<Policy> policies = policyRepository.findAll();
        int applied = 0;
        for (McpServerTools server : runtimeClient.listTools(true)) {
            for (McpToolInfo tool : server.tools()) {
                String action = action(server.server(), tool.name());
                if (toolPolicy(action, policies).isEmpty()) {
                    policyManagementService.create(new PolicyRequest(action, null, suggest(tool.annotations()), null));
                    applied++;
                }
            }
        }
        return applied;
    }

    /**
     * A starting point from MCP tool annotations, which the server reports about itself and
     * so are hints only: read-only → LOW, explicitly non-destructive → MEDIUM, otherwise HIGH
     * (MCP treats tools as destructive unless they say otherwise).
     */
    static RiskLevel suggest(JsonNode annotations) {
        if (annotations == null || annotations.isNull()) {
            return RiskLevel.HIGH;
        }
        if (annotations.path("readOnlyHint").asBoolean(false)) {
            return RiskLevel.LOW;
        }
        JsonNode destructive = annotations.path("destructiveHint");
        if (destructive.isBoolean() && !destructive.asBoolean()) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.HIGH;
    }

    private List<ToolRisk.Tool> tools(McpServerTools server, List<Policy> policies) {
        return server.tools().stream().map(tool -> {
            String action = action(server.server(), tool.name());
            Optional<Policy> policy = toolPolicy(action, policies);
            RiskLevel set = policy.map(Policy::getRiskLevel).orElse(null);
            return new ToolRisk.Tool(tool.name(), tool.title(), tool.description(), action, set,
                    policy.map(Policy::getId).orElse(null),
                    set != null ? set : RiskEvaluationService.DEFAULT_RISK_LEVEL,
                    suggest(tool.annotations()));
        }).toList();
    }

    // The policy that decides the action without labels: the oldest one, as PolicyMatcher picks it.
    private static Optional<Policy> toolPolicy(String action, List<Policy> policies) {
        return policies.stream()
                .filter(p -> isToolPolicy(p, action))
                .min(Comparator.comparing(Policy::getId));
    }

    private static boolean isToolPolicy(Policy policy, String action) {
        return action.equals(policy.getActionType()) && policy.getLabel() == null;
    }
}
