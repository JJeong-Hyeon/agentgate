package com.agentgate.memory.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentMemoryRepositoryTest {

    @Test
    void termsAreDistinctLowercaseWordsOfAtLeastTwoCharacters() {
        assertThat(AgentMemoryRepository.terms("고객 배송, 배송 a VIP 문의?"))
                .containsExactly("고객", "배송", "vip", "문의");
    }

    @Test
    void termsAreCappedAtEight() {
        assertThat(AgentMemoryRepository.terms("aa bb cc dd ee ff gg hh ii jj")).hasSize(8);
    }

    @Test
    void likeWildcardsAreEscaped() {
        assertThat(AgentMemoryRepository.escapeLike("100%_a\\b")).isEqualTo("100\\%\\_a\\\\b");
    }

    @Test
    void vectorLiteralIsPgvectorText() {
        assertThat(AgentMemoryRepository.literal(new float[] {1f, -0.5f})).isEqualTo("[1.0,-0.5]");
    }
}
