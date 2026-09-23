package com.agentgate.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecurityHardeningCheckTest {

    @Test
    void throwsWhenPasswordIsDefault() {
        SecurityHardeningCheck check = new SecurityHardeningCheck("changeme");

        assertThatThrownBy(() -> check.run(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void passesWhenPasswordIsOverridden() {
        SecurityHardeningCheck check = new SecurityHardeningCheck("a-real-secret");

        assertThatCode(() -> check.run(null)).doesNotThrowAnyException();
    }
}
