package com.agentgate.risk;

import com.agentgate.policy.domain.Policy;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class PolicyMatcher {

    private PolicyMatcher() {
    }

    public static Optional<Policy> findBestMatch(String action, List<String> labels, List<Policy> policies) {
        return policies.stream()
                .filter(policy -> matchesAction(policy, action) && matchesLabel(policy, labels))
                .max(Comparator.comparingInt(PolicyMatcher::specificity)
                        .thenComparing(Policy::getId, Comparator.reverseOrder()));
    }

    private static boolean matchesAction(Policy policy, String action) {
        return policy.getActionType() == null || policy.getActionType().equals(action);
    }

    private static boolean matchesLabel(Policy policy, List<String> labels) {
        return policy.getLabel() == null || labels.contains(policy.getLabel());
    }

    private static int specificity(Policy policy) {
        int score = 0;
        if (policy.getActionType() != null) {
            score += 2;
        }
        if (policy.getLabel() != null) {
            score += 1;
        }
        return score;
    }
}
