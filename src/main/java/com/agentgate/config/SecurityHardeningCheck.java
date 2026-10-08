package com.agentgate.config;

import com.agentgate.common.security.SecretCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
public class SecurityHardeningCheck implements ApplicationRunner {

    private static final String DEFAULT_DEV_PASSWORD = "changeme";

    private final String adminPassword;
    private final String secretKey;

    public SecurityHardeningCheck(@Value("${agentgate.admin.password}") String adminPassword,
                                  @Value("${agentgate.secrets.key:}") String secretKey) {
        this.adminPassword = adminPassword;
        this.secretKey = secretKey;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (DEFAULT_DEV_PASSWORD.equals(adminPassword)) {
            throw new IllegalStateException(
                    "Refusing to start with the default dev admin password in the 'prod' profile. "
                            + "Set AGENTGATE_ADMIN_PASSWORD to a real value.");
        }
        if (!SecretCipher.isConfigured(secretKey)) {
            throw new IllegalStateException(
                    "Refusing to start without AGENTGATE_SECRET_KEY in the 'prod' profile: stored credentials "
                            + "(e.g. MCP server headers) would use the development key. "
                            + "Generate one with: openssl rand -base64 32");
        }
    }
}
