package com.agentgate.user.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

class OidcLogoutUrlTest {

    private static OidcLoginSupport support(Map<String, Object> metadata) {
        ClientRegistration registration = ClientRegistration.withRegistrationId("oidc")
                .clientId("agentgate").clientSecret("s")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("http://idp/auth").tokenUri("http://idp/token").jwkSetUri("http://idp/certs")
                .providerConfigurationMetadata(metadata)
                .build();
        OidcProperties properties = new OidcProperties(true, "http://idp", "agentgate", "s", null, null, null,
                null, null, null, null, null, null);
        return new OidcLoginSupport(properties, id -> registration, mock(OidcRoleMapper.class),
                mock(OidcUserProvisioner.class));
    }

    private static Authentication ssoLogin() {
        Instant now = Instant.now();
        var idToken = new OidcIdToken("the.id.token", now, now.plusSeconds(60), Map.of("sub", "u1"));
        var principal = new DefaultOidcUser(List.of(), idToken);
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    @Test
    void anSsoSignInEndsTheIdpSessionToo() {
        String url = support(Map.of("end_session_endpoint", "http://idp/logout"))
                .endSessionUrl(ssoLogin(), "https://agentgate.example.com/");

        assertThat(url).startsWith("http://idp/logout?")
                .contains("id_token_hint=the.id.token")
                .contains("post_logout_redirect_uri=https://agentgate.example.com/");
    }

    @Test
    void aPasswordSignInOrAnIdpWithoutALogoutEndpointHasNoIdpSessionToEnd() {
        Authentication local = new UsernamePasswordAuthenticationToken("admin", null, List.of());

        assertThat(support(Map.of("end_session_endpoint", "http://idp/logout"))
                .endSessionUrl(local, "https://agentgate.example.com/")).isNull();
        assertThat(support(Map.of()).endSessionUrl(ssoLogin(), "https://agentgate.example.com/")).isNull();
        assertThat(support(Map.of()).endSessionUrl(null, "https://agentgate.example.com/")).isNull();
    }
}
