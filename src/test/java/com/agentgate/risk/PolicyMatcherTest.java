package com.agentgate.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentgate.policy.domain.Policy;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PolicyMatcherTest {

    @Test
    void exactActionAndLabelMatchWinsOverWildcard() {
        Policy wildcard = policy(1L, null, null, RiskLevel.LOW);
        Policy actionOnly = policy(2L, "SEND_EMAIL", null, RiskLevel.MEDIUM);
        Policy actionAndLabel = policy(3L, "SEND_EMAIL", "PII", RiskLevel.HIGH);

        Optional<Policy> result = PolicyMatcher.findBestMatch(
                "SEND_EMAIL", List.of("PII"), List.of(wildcard, actionOnly, actionAndLabel));

        assertThat(result).contains(actionAndLabel);
    }

    @Test
    void noMatchReturnsEmpty() {
        Policy actionOnly = policy(1L, "SEND_EMAIL", null, RiskLevel.MEDIUM);

        Optional<Policy> result = PolicyMatcher.findBestMatch("DELETE_DATA", List.of(), List.of(actionOnly));

        assertThat(result).isEmpty();
    }

    @Test
    void tieBreaksToLowestId() {
        Policy first = policy(1L, "SEND_EMAIL", null, RiskLevel.MEDIUM);
        Policy second = policy(2L, "SEND_EMAIL", null, RiskLevel.HIGH);

        Optional<Policy> result = PolicyMatcher.findBestMatch("SEND_EMAIL", List.of(), List.of(second, first));

        assertThat(result).contains(first);
    }

    private static Policy policy(Long id, String actionType, String label, RiskLevel riskLevel) {
        Policy policy = new Policy(actionType, label, riskLevel);
        setId(policy, id);
        return policy;
    }

    private static void setId(Policy policy, Long id) {
        try {
            Field field = Policy.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(policy, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
