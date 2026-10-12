package com.agentgate.risk;

import com.agentgate.policy.domain.Policy;
import com.agentgate.policy.repository.PolicyRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RiskEvaluationService {

    // For actions no policy matches: unknown actions need approval.
    public static final RiskLevel DEFAULT_RISK_LEVEL = RiskLevel.HIGH;
    // Delegating to another agent touches no outside system; the delegate's own tool calls are governed.
    public static final RiskLevel DEFAULT_DELEGATION_RISK_LEVEL = RiskLevel.MEDIUM;
    // An agent's own memory lives in AgentGate; the definition's read/write permissions and policies govern it.
    public static final RiskLevel DEFAULT_MEMORY_RISK_LEVEL = RiskLevel.MEDIUM;

    private final PolicyRepository policyRepository;

    public RiskEvaluationResult evaluate(String action, List<String> labels) {
        List<Policy> policies = policyRepository.findAll();
        Optional<Policy> match = PolicyMatcher.findBestMatch(action, labels, policies);
        RiskLevel riskLevel = match.map(Policy::getRiskLevel).orElseGet(() -> defaultRiskLevel(action));
        return new RiskEvaluationResult(riskLevel, riskLevel.toActionStatus());
    }

    private static RiskLevel defaultRiskLevel(String action) {
        if (action.startsWith("AGENT:")) {
            return DEFAULT_DELEGATION_RISK_LEVEL;
        }
        if (action.startsWith("MEMORY:")) {
            return DEFAULT_MEMORY_RISK_LEVEL;
        }
        return DEFAULT_RISK_LEVEL;
    }
}
