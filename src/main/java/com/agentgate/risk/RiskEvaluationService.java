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

    private static final RiskLevel DEFAULT_RISK_LEVEL = RiskLevel.HIGH;

    private final PolicyRepository policyRepository;

    public RiskEvaluationResult evaluate(String action, List<String> labels) {
        List<Policy> policies = policyRepository.findAll();
        Optional<Policy> match = PolicyMatcher.findBestMatch(action, labels, policies);
        RiskLevel riskLevel = match.map(Policy::getRiskLevel).orElse(DEFAULT_RISK_LEVEL);
        return new RiskEvaluationResult(riskLevel, riskLevel.toActionStatus());
    }
}
