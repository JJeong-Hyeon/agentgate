package com.agentgate.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SpaRoutingTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void clientRoutesForwardToIndexWithoutLogin() throws Exception {
        for (String path : new String[] {"/", "/workflows/research/edit", "/executions/abc", "/approvals"}) {
            mockMvc.perform(get(path)).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        }
    }

    @Test
    void apiStillRequiresLogin() throws Exception {
        mockMvc.perform(get("/api/v1/workflows")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/workflows").with(httpBasic("test-admin", "wrong")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ajaxRequestsGetNoBasicChallenge() throws Exception {
        mockMvc.perform(get("/api/v1/workflows").header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("WWW-Authenticate"));
        mockMvc.perform(get("/api/v1/workflows"))
                .andExpect(header().exists("WWW-Authenticate"));
    }

    @Test
    void otherPathsStayDenied() throws Exception {
        mockMvc.perform(get("/secret")).andExpect(status().isUnauthorized());
    }
}
