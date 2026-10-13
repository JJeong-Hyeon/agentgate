package com.agentgate.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class LoginThrottleFilterTest {

    @Test
    void readsTheUsernameOfBasicCredentials() {
        String token = Base64.getEncoder().encodeToString("alice:pa:ss".getBytes(StandardCharsets.UTF_8));

        assertThat(LoginThrottleFilter.basicUsername("Basic " + token)).contains("alice");
        assertThat(LoginThrottleFilter.basicUsername("basic " + token)).contains("alice");
    }

    @Test
    void ignoresOtherOrMalformedHeaders() {
        assertThat(LoginThrottleFilter.basicUsername(null)).isEmpty();
        assertThat(LoginThrottleFilter.basicUsername("Bearer abc")).isEmpty();
        assertThat(LoginThrottleFilter.basicUsername("Basic !!!")).isEmpty();
        String noColon = Base64.getEncoder().encodeToString("alice".getBytes(StandardCharsets.UTF_8));
        assertThat(LoginThrottleFilter.basicUsername("Basic " + noColon)).isEmpty();
    }
}
