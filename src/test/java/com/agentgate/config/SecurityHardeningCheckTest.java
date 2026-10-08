package com.agentgate.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecurityHardeningCheckTest {

    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    void throwsWhenPasswordIsDefault() {
        SecurityHardeningCheck check = new SecurityHardeningCheck("changeme", KEY);

        assertThatThrownBy(() -> check.run(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void throwsWithoutASecretKey() {
        SecurityHardeningCheck check = new SecurityHardeningCheck("a-real-secret", "");

        assertThatThrownBy(() -> check.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AGENTGATE_SECRET_KEY");
    }

    @Test
    void passesWhenPasswordAndKeyAreSet() {
        SecurityHardeningCheck check = new SecurityHardeningCheck("a-real-secret", KEY);

        assertThatCode(() -> check.run(null)).doesNotThrowAnyException();
    }
}
