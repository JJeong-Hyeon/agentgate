package com.agentgate.user.oidc;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

@Configuration
@EnableConfigurationProperties(OidcProperties.class)
@ConditionalOnProperty(name = "agentgate.oidc.enabled", havingValue = "true")
public class OidcConfig {

    @Bean
    ClientRegistrationRepository oidcClientRegistrations(OidcProperties properties) {
        return new LazyClientRegistrationRepository(properties);
    }

    // The provisioner is the Spring bean, not a copy: its transaction is what saves a role change.
    @Bean
    OidcLoginSupport oidcLoginSupport(OidcProperties properties, ClientRegistrationRepository registrations,
                                      OidcUserProvisioner provisioner) {
        return new OidcLoginSupport(properties, registrations, new OidcRoleMapper(properties), provisioner);
    }
}
