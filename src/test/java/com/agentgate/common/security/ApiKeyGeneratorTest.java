package com.agentgate.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiKeyGeneratorTest {

    @Test
    void generateProducesDifferentKeysEachTime() {
        String first = ApiKeyGenerator.generate();
        String second = ApiKeyGenerator.generate();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void hashIsDeterministic() {
        String key = "some-key";

        assertThat(ApiKeyGenerator.hash(key)).isEqualTo(ApiKeyGenerator.hash(key));
    }

    @Test
    void differentKeysHashToDifferentValues() {
        assertThat(ApiKeyGenerator.hash("key-a")).isNotEqualTo(ApiKeyGenerator.hash("key-b"));
    }
}
