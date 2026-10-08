package com.agentgate.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String OTHER_KEY = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=";

    @Test
    void roundTripsWithAFreshIvEachTime() {
        SecretCipher cipher = new SecretCipher(KEY);

        String first = cipher.encrypt("Bearer token-123");
        String second = cipher.encrypt("Bearer token-123");

        assertThat(first).isNotEqualTo(second).doesNotContain("token-123");
        assertThat(cipher.decrypt(first)).isEqualTo("Bearer token-123");
        assertThat(cipher.decrypt(second)).isEqualTo("Bearer token-123");
    }

    @Test
    void detectsTamperingAndWrongKeys() {
        String sealed = new SecretCipher(KEY).encrypt("secret");
        byte[] bytes = Base64.getDecoder().decode(sealed);
        bytes[bytes.length - 1] ^= 1;

        assertThatThrownBy(() -> new SecretCipher(KEY).decrypt(Base64.getEncoder().encodeToString(bytes)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SecretCipher(OTHER_KEY).decrypt(sealed))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsKeysOfTheWrongSize() {
        assertThatThrownBy(() -> new SecretCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fallsBackToTheDevelopmentKey() {
        SecretCipher cipher = new SecretCipher("");

        assertThat(cipher.decrypt(cipher.encrypt("x"))).isEqualTo("x");
        assertThat(SecretCipher.isConfigured("")).isFalse();
    }
}
