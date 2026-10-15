package com.agentgate.user.oidc;

import com.agentgate.user.domain.Role;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sign-in through the company identity provider (OpenID Connect, authorization code with PKCE).
 * Off unless {@code enabled}. The groups the IdP puts in {@code groupsClaim} decide the role: a
 * group listed under several roles grants all of them, and a person in none of the listed groups
 * gets {@code defaultRole}, or is refused when that is empty (the default).
 */
@ConfigurationProperties("agentgate.oidc")
public record OidcProperties(
        boolean enabled,
        String issuerUri,
        String clientId,
        String clientSecret,
        List<String> scopes,
        String usernameClaim,
        String groupsClaim,
        List<String> adminGroups,
        List<String> editorGroups,
        List<String> approverGroups,
        List<String> viewerGroups,
        Role defaultRole,
        String buttonLabel) {

    public OidcProperties {
        scopes = scopes == null || scopes.isEmpty() ? List.of("openid", "profile", "email") : scopes;
        usernameClaim = blankTo(usernameClaim, "preferred_username");
        groupsClaim = blankTo(groupsClaim, "groups");
        adminGroups = adminGroups == null ? List.of() : adminGroups;
        editorGroups = editorGroups == null ? List.of() : editorGroups;
        approverGroups = approverGroups == null ? List.of() : approverGroups;
        viewerGroups = viewerGroups == null ? List.of() : viewerGroups;
        buttonLabel = blankTo(buttonLabel, "SSO");
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
