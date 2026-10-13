package com.agentgate.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Console sign-in with a session cookie, next to HTTP Basic for scripts. */
@SpringBootTest(properties = {"agentgate.login.max-failures=3", "agentgate.login.max-failures-per-ip=50"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionLoginIntegrationTest {

    @Autowired private MockMvc mockMvc;

    private MvcResult signIn(String password, String ip) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"test-admin\",\"password\":\"%s\"}".formatted(password))
                        .with(request -> {
                            request.setRemoteAddr(ip);
                            return request;
                        }))
                .andReturn();
    }

    private static MockHttpSession sessionOf(MvcResult result) {
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void signingInGivesASessionThatAuthenticatesLaterRequestsWithoutThePassword() throws Exception {
        MvcResult login = signIn("test-password", "10.9.0.1");

        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        assertThat(login.getResponse().getContentAsString()).contains("\"username\":\"test-admin\"")
                .contains("ADMIN").doesNotContain("password");
        MockHttpSession session = sessionOf(login);
        assertThat(session).isNotNull();

        mockMvc.perform(get("/api/v1/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("test-admin"));
        mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongCredentialsAreRejectedWithoutASessionOrABrowserSignInDialog() throws Exception {
        MvcResult login = signIn("wrong", "10.9.0.2");

        assertThat(login.getResponse().getStatus()).isEqualTo(401);
        assertThat(login.getResponse().getHeader("WWW-Authenticate")).isNull();
        assertThat(login.getResponse().getContentAsString()).contains("INVALID_CREDENTIALS");
        assertThat(sessionOf(login)).isNull();
    }

    @Test
    void repeatedFailuresAreThrottledAndASignInThenFailsEvenWithTheRightPassword() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(signIn("wrong", "10.9.0.3").getResponse().getStatus()).isEqualTo(401);
        }

        MvcResult blocked = signIn("test-password", "10.9.0.3");

        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        assertThat(blocked.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(signIn("test-password", "10.9.0.4").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void aSessionNeedsTheCsrfTokenToChangeThingsButHeaderCredentialsDoNot() throws Exception {
        MockHttpSession session = sessionOf(signIn("test-password", "10.9.0.5"));
        String csrf = mockMvc.perform(get("/api/v1/me").session(session)).andExpect(status().isOk())
                .andExpect(header().exists("Set-Cookie")).andReturn().getResponse().getCookie("XSRF-TOKEN").getValue();

        mockMvc.perform(post("/api/v1/auth/logout").session(session)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/auth/logout").session(session)
                        .cookie(new Cookie("XSRF-TOKEN", csrf)).header("X-XSRF-TOKEN", "forged"))
                .andExpect(status().isForbidden());
        // Basic credentials are not ambient: a script needs no token.
        mockMvc.perform(post("/api/v1/auth/logout").with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/logout").session(session)
                        .cookie(new Cookie("XSRF-TOKEN", csrf)).header("X-XSRF-TOKEN", csrf))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mockMvc.perform(get("/api/v1/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void anUnauthenticatedRequestDoesNotStartASession() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/workflows")).andExpect(status().isUnauthorized()).andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
    }
}
