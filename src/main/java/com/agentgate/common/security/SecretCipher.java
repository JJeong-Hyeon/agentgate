package com.agentgate.common.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts secrets stored in the database (e.g. MCP server credentials) with AES-256-GCM.
 * The key is {@code agentgate.secrets.key}: 32 random bytes, Base64 encoded. Without one a fixed
 * development key is used, which the prod profile refuses ({@code SecurityHardeningCheck}).
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);
    private static final String DEV_KEY = "YWdlbnRnYXRlLWRldi1vbmx5LXNlY3JldC1rZXktMzI=";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${agentgate.secrets.key:}") String base64Key) {
        String encoded = base64Key;
        if (encoded.isBlank()) {
            log.warn("agentgate.secrets.key is not set; using the development key. Set AGENTGATE_SECRET_KEY.");
            encoded = DEV_KEY;
        }
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length != 32) {
            throw new IllegalStateException("agentgate.secrets.key must be 32 bytes, Base64 encoded");
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    public static boolean isConfigured(String base64Key) {
        return base64Key != null && !base64Key.isBlank();
    }

    /** Base64 of IV followed by ciphertext and tag. */
    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt secret", e);
        }
    }

    /** @throws IllegalStateException when the value was tampered with or encrypted with another key */
    public String decrypt(String encrypted) {
        try {
            byte[] all = Base64.getDecoder().decode(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not decrypt secret (wrong key or tampered value)", e);
        }
    }
}
