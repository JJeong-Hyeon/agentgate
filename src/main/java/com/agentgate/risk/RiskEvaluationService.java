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

    private final PolicyRepository policyRepository;

    public RiskEvaluationResult evaluate(String action, List<String> labels) {
        List<Policy> policies = policyRepository.findAll();
        Optional<Policy> match = PolicyMatcher.findBestMatch(action, labels, policies);
        RiskLevel riskLevel = match.map(Policy::getRiskLevel).orElse(
                action.startsWith("AGENT:") ? DEFAULT_DELEGATION_RISK_LEVEL : DEFAULT_RISK_LEVEL);
        return new RiskEvaluationResult(riskLevel, riskLevel.toActionStatus());
    }
}
