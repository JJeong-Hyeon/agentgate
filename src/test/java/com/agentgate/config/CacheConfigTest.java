package com.agentgate.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentgate.policy.domain.Policy;
import com.agentgate.policy.domain.PolicyCategory;
import com.agentgate.risk.RiskLevel;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializer;
import tools.jackson.databind.json.JsonMapper;

class CacheConfigTest {

    @Test
    void policiesSerializerRoundTripsPolicyEntities() {
        RedisSerializer<List<Policy>> serializer = CacheConfig.policiesSerializer(new JsonMapper());
        List<Policy> policies = List.of(
                new Policy(null, "PII", RiskLevel.HIGH, PolicyCategory.PRIVACY),
                new Policy("DELETE_DATA", null, RiskLevel.BLOCKED));

        List<Policy> restored = serializer.deserialize(serializer.serialize(policies));

        assertThat(restored).hasSize(2).allSatisfy(p -> assertThat(p).isInstanceOf(Policy.class));
        assertThat(restored.get(0).getLabel()).isEqualTo("PII");
        assertThat(restored.get(0).getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(restored.get(0).getCategory()).isEqualTo(PolicyCategory.PRIVACY);
        assertThat(restored.get(1).getActionType()).isEqualTo("DELETE_DATA");
    }
}
