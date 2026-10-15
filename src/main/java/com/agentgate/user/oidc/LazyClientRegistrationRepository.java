package com.agentgate.user.oidc;

import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * The one IdP registration, discovered from its issuer on first use. Looking it up at startup would
 * keep AgentGate (and so the local administrator) from starting whenever the IdP is down.
 */
public class LazyClientRegistrationRepository implements ClientRegistrationRepository {

    public static final String REGISTRATION_ID = "oidc";

    private static final Logger log = LoggerFactory.getLogger(LazyClientRegistrationRepository.class);

    private final OidcProperties properties;
    private final AtomicReference<ClientRegistration> registration = new AtomicReference<>();

    public LazyClientRegistrationRepository(OidcProperties properties) {
        this.properties = properties;
    }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        if (!REGISTRATION_ID.equals(registrationId)) {
            return null;
        }
        ClientRegistration known = registration.get();
        if (known != null) {
            return known;
        }
        try {
            ClientRegistration discovered = ClientRegistrations.fromIssuerLocation(properties.issuerUri())
                    .registrationId(REGISTRATION_ID)
                    .clientId(properties.clientId())
                    .clientSecret(properties.clientSecret())
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .scope(properties.scopes())
                    .userNameAttributeName(properties.usernameClaim())
                    .build();
            registration.compareAndSet(null, discovered);
            return registration.get();
        } catch (RuntimeException e) {
            // Not cached: the next attempt tries again once the IdP is back.
            log.warn("could not reach the OIDC identity provider at {}: {}", properties.issuerUri(), e.toString());
            return null;
        }
    }
}
