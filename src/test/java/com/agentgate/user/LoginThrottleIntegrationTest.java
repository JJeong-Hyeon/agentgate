package com.agentgate.user;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Repeated failed sign-ins through the real security filter chain. */
@SpringBootTest(properties = {"agentgate.login.max-failures=3", "agentgate.login.max-failures-per-ip=6"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginThrottleIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void aBlockedUserIsRefusedEvenWithTheRightPasswordButOnlyFromThatAddress() throws Exception {
        for (int i = 0; i < 3; i++) {
            signIn("test-admin", "wrong", "10.1.0.1").andExpect(status().isUnauthorized());
        }

        signIn("test-admin", "test-password", "10.1.0.1")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_LOGIN_ATTEMPTS"));
        signIn("test-admin", "test-password", "10.1.0.2").andExpect(status().isOk());
    }

    @Test
    void aSuccessfulSignInResetsTheCount() throws Exception {
        for (int i = 0; i < 2; i++) {
            signIn("test-admin", "wrong", "10.2.0.1").andExpect(status().isUnauthorized());
        }
        signIn("test-admin", "test-password", "10.2.0.1").andExpect(status().isOk());
        for (int i = 0; i < 2; i++) {
            signIn("test-admin", "wrong", "10.2.0.1").andExpect(status().isUnauthorized());
        }

        signIn("test-admin", "test-password", "10.2.0.1").andExpect(status().isOk());
    }

    @Test
    void anAddressGuessingManyUsernamesIsBlocked() throws Exception {
        for (int i = 0; i < 6; i++) {
            signIn("user" + i, "wrong", "10.3.0.1").andExpect(status().isUnauthorized());
        }

        signIn("test-admin", "test-password", "10.3.0.1").andExpect(status().isTooManyRequests());
    }

    @Test
    void requestsWithoutCredentialsAreNotCounted() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/v1/me").with(remoteAddr("10.4.0.1"))).andExpect(status().isUnauthorized());
        }

        signIn("test-admin", "test-password", "10.4.0.1").andExpect(status().isOk());
    }

    private ResultActions signIn(String username, String password, String ip) throws Exception {
        return mockMvc.perform(get("/api/v1/me").with(httpBasic(username, password)).with(remoteAddr(ip)));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }
}
