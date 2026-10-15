package com.agentgate.user.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** SSO switched on, against a stand-in IdP that only publishes its discovery document. */
@SpringBootTest(properties = {
        "agentgate.oidc.enabled=true",
        "agentgate.oidc.client-id=agentgate",
        "agentgate.oidc.client-secret=secret",
        "agentgate.oidc.admin-groups=agentgate-admins",
        "agentgate.oidc.viewer-groups=agentgate-viewers",
        // What the selfhost compose passes for settings left empty in .env.
        "agentgate.oidc.editor-groups=", "agentgate.oidc.default-role=", "agentgate.oidc.username-claim=",
        "agentgate.oidc.button-label=",
        "agentgate.auth.local-login-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OidcLoginIntegrationTest {

    private static final HttpServer IDP = startIdp();

    @Autowired private MockMvc mockMvc;
    @Autowired private OidcLoginSupport oidcLogin;
    @Autowired private com.agentgate.user.repository.UserRepository users;

    private static HttpServer startIdp() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/test";
            String metadata = """
                    {"issuer":"%1$s","authorization_endpoint":"%1$s/auth","token_endpoint":"%1$s/token",
                     "jwks_uri":"%1$s/certs","response_types_supported":["code"],
                     "subject_types_supported":["public"],"id_token_signing_alg_values_supported":["RS256"],
                     "grant_types_supported":["authorization_code"]}""".formatted(issuer);
            server.createContext("/realms/test/.well-known/openid-configuration", exchange -> {
                byte[] body = metadata.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void idp(DynamicPropertyRegistry registry) {
        registry.add("agentgate.oidc.issuer-uri", () -> "http://127.0.0.1:" + IDP.getAddress().getPort() + "/realms/test");
    }

    @AfterAll
    static void stopIdp() {
        IDP.stop(0);
    }

    @Test
    void theLoginPageIsToldHowToSignIn() throws Exception {
        mockMvc.perform(get("/api/v1/auth/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ssoEnabled").value(true))
                .andExpect(jsonPath("$.ssoLabel").value("SSO"))
                .andExpect(jsonPath("$.localLoginEnabled").value(false));
    }

    @Test
    void ssoSignInSendsTheBrowserToTheIdpWithPkceAndState() throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth2/authorization/oidc")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).startsWith(IDP_ISSUER() + "/auth?")
                .contains("response_type=code", "client_id=agentgate", "code_challenge_method=S256",
                        "code_challenge=", "state=", "nonce=")
                .contains("redirect_uri=http://localhost/login/oauth2/code/oidc");
    }

    @Test
    void apiRequestsWithoutASessionStayUnauthorizedInsteadOfRedirectingToTheIdp() throws Exception {
        mockMvc.perform(get("/api/v1/agents")).andExpect(status().isUnauthorized());
    }

    @Test
    void passwordSignInCanBeSwitchedOff() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"test-admin\",\"password\":\"test-password\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LOCAL_LOGIN_DISABLED"));
    }

    @Test
    void scriptsCanStillUseBasicAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/me").with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk());
    }

    private static org.springframework.security.oauth2.core.oidc.user.OidcUser idpUser(String name, java.util.List<String> groups) {
        var now = java.time.Instant.now();
        var token = new org.springframework.security.oauth2.core.oidc.OidcIdToken("t", now, now.plusSeconds(60),
                java.util.Map.of("sub", name, "preferred_username", name, "groups", groups));
        return new org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser(java.util.List.of(), token);
    }

    @Test
    void aRoleChangeAtTheIdpIsSavedAtTheNextSignIn() {
        oidcLogin.toConsoleUser(idpUser("sync-hana", java.util.List.of("agentgate-admins")));
        oidcLogin.toConsoleUser(idpUser("sync-hana", java.util.List.of("agentgate-viewers")));

        var stored = users.findByUsername("sync-hana").orElseThrow();
        assertThat(stored.getRoles()).containsExactly(com.agentgate.user.domain.Role.VIEWER);
        users.delete(stored);
    }

    private static String IDP_ISSUER() {
        return "http://127.0.0.1:" + IDP.getAddress().getPort() + "/realms/test";
    }
}
