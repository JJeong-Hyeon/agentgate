package com.agentgate.user.oidc;

import com.agentgate.user.domain.Role;
import com.agentgate.user.domain.User;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Plugs "sign in with the company IdP" into the security filter chain. The IdP's ID token is
 * verified by Spring Security (signature against the IdP's published keys, issuer, audience,
 * expiry, nonce); what this adds is turning its claims into a console user and roles.
 */
public class OidcLoginSupport {

    private static final Logger log = LoggerFactory.getLogger(OidcLoginSupport.class);

    private final OidcProperties properties;
    private final ClientRegistrationRepository registrations;
    private final OidcRoleMapper roleMapper;
    private final OidcUserProvisioner provisioner;

    public OidcLoginSupport(OidcProperties properties, ClientRegistrationRepository registrations,
                            OidcRoleMapper roleMapper, OidcUserProvisioner provisioner) {
        this.properties = properties;
        this.registrations = registrations;
        this.roleMapper = roleMapper;
        this.provisioner = provisioner;
    }

    public OidcProperties properties() {
        return properties;
    }

    public void apply(HttpSecurity http) throws Exception {
        DefaultOAuth2AuthorizationRequestResolver resolver =
                new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        // PKCE as well as the client secret, so a stolen authorization code is useless on its own.
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        http.oauth2Login(login -> login
                .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(resolver))
                .userInfoEndpoint(info -> info.oidcUserService(userService()))
                .failureHandler((request, response, exception) -> {
                    log.warn("SSO sign-in failed: {}", exception.getMessage());
                    response.sendRedirect("/?sso_error=1");
                }));
    }

    /**
     * Where to send the browser to end the person's session at the IdP too (OIDC RP-initiated logout),
     * or null when they did not sign in through it or the IdP has no such endpoint.
     */
    public String endSessionUrl(Authentication authentication, String postLogoutRedirectUri) {
        if (authentication == null || !(authentication.getPrincipal() instanceof OidcUser user)) {
            return null;
        }
        ClientRegistration registration = registrations.findByRegistrationId(LazyClientRegistrationRepository.REGISTRATION_ID);
        if (registration == null) {
            return null;
        }
        Object endpoint = registration.getProviderDetails().getConfigurationMetadata().get("end_session_endpoint");
        if (endpoint == null) {
            return null;
        }
        return UriComponentsBuilder.fromUriString(endpoint.toString())
                .queryParam("id_token_hint", user.getIdToken().getTokenValue())
                .queryParam("post_logout_redirect_uri", postLogoutRedirectUri)
                .build().encode().toUriString();
    }

    OAuth2UserService<OidcUserRequest, OidcUser> userService() {
        OidcUserService idp = new OidcUserService();
        return request -> toConsoleUser(idp.loadUser(request));
    }

    OidcUser toConsoleUser(OidcUser loaded) {
        String username = loaded.getClaimAsString(properties.usernameClaim());
        String displayName = loaded.getClaimAsString("name");
        List<String> groups = groupsOf(loaded);
        User user;
        try {
            user = provisioner.signIn(loaded.getSubject(), username, displayName, roleMapper.rolesFor(groups));
        } catch (OidcUserProvisioner.Refused e) {
            log.warn("SSO sign-in refused: {}", e.getMessage());
            throw new OAuth2AuthenticationException(new OAuth2Error("access_denied"), e.getMessage());
        }
        Set<GrantedAuthority> authorities = user.getRoles().stream()
                .map(Role::name)
                .map(name -> new SimpleGrantedAuthority("ROLE_" + name))
                .collect(Collectors.toSet());
        return new DefaultOidcUser(authorities, loaded.getIdToken(), loaded.getUserInfo(), properties.usernameClaim());
    }

    private List<String> groupsOf(OidcUser user) {
        List<String> groups = user.getClaimAsStringList(properties.groupsClaim());
        return groups == null ? List.of() : groups;
    }
}
