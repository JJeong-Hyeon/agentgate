package com.agentgate.config;

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

    public SecurityHardeningCheck(@Value("${agentgate.admin.password}") String adminPassword) {
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (DEFAULT_DEV_PASSWORD.equals(adminPassword)) {
            throw new IllegalStateException(
                    "Refusing to start with the default dev admin password in the 'prod' profile. "
                            + "Set AGENTGATE_ADMIN_PASSWORD to a real value.");
        }
    }
}
