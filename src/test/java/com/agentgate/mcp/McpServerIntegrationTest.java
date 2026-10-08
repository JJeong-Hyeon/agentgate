package com.agentgate.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentgate.mcp.repository.McpServerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "agentgate.runtime.base-url=http://localhost:1",
        "agentgate.runtime.token=runtime-secret"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class McpServerIntegrationTest {

    private static final String CRM = """
            {"name":"crm","url":"https://crm.internal/mcp","description":"CRM tools",
             "headers":{"Authorization":"Bearer crm-token","X-Tenant":"acme"}}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private McpServerRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    private ResultActions admin(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(httpBasic("test-admin", "test-password")));
    }

    private long create(String body) throws Exception {
        String response = admin(post("/api/v1/mcp-servers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new JsonMapper().readTree(response).get("id").asLong();
    }

    private ResultActions runtime(String token) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/runtime/mcp-servers");
        return mockMvc.perform(token == null ? request : request.header("X-Runtime-Token", token));
    }

    @Test
    void registersAServerWithoutEverShowingItsHeaders() throws Exception {
        long id = create(CRM);

        admin(get("/api/v1/mcp-servers/{id}", id))
                .andExpect(jsonPath("$.name").value("crm"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.headerNames[0]").value("Authorization"))
                .andExpect(jsonPath("$.headerNames[1]").value("X-Tenant"))
                .andExpect(jsonPath("$.headers").doesNotExist());
        String listed = admin(get("/api/v1/mcp-servers")).andReturn().getResponse().getContentAsString();
        assertThat(listed).doesNotContain("crm-token");
        // Stored encrypted, not in plain text.
        assertThat(repository.findAll().get(0).getHeadersEncrypted()).doesNotContain("crm-token");
    }

    @Test
    void runtimeGetsEnabledServersWithTheirHeaders() throws Exception {
        create(CRM);
        create("{\"name\":\"off\",\"url\":\"http://off:8000/mcp\",\"enabled\":false}");

        runtime("runtime-secret")
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("crm"))
                .andExpect(jsonPath("$[0].url").value("https://crm.internal/mcp"))
                .andExpect(jsonPath("$[0].headers.Authorization").value("Bearer crm-token"));
        runtime("wrong").andExpect(status().isUnauthorized());
        runtime(null).andExpect(status().isUnauthorized());
    }

    @Test
    void updateKeepsHeadersUnlessGivenAndNeverRenames() throws Exception {
        long id = create(CRM);

        admin(put("/api/v1/mcp-servers/{id}", id).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"renamed","url":"https://crm2.internal/mcp","enabled":false}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("crm"))
                .andExpect(jsonPath("$.url").value("https://crm2.internal/mcp"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.headerNames.length()").value(2));

        admin(put("/api/v1/mcp-servers/{id}", id).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"crm","url":"https://crm2.internal/mcp","headers":{}}"""))
                .andExpect(jsonPath("$.headerNames.length()").value(0))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void rejectsDuplicatesAndInvalidInput() throws Exception {
        create(CRM);
        admin(post("/api/v1/mcp-servers").contentType(MediaType.APPLICATION_JSON).content(CRM))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MCP_SERVER_ALREADY_EXISTS"));
        for (String body : new String[] {
                "{\"name\":\"bad name\",\"url\":\"http://x/mcp\"}",
                "{\"name\":\"files\",\"url\":\"file:///etc/passwd\"}",
                "{\"name\":\"files\",\"url\":\"npx server-filesystem\"}",
                "{\"name\":\"h\",\"url\":\"http://x/mcp\",\"headers\":{\"Bad Header\":\"v\"}}"}) {
            admin(post("/api/v1/mcp-servers").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void deletesAndReportsMissingServers() throws Exception {
        long id = create(CRM);

        admin(delete("/api/v1/mcp-servers/{id}", id)).andExpect(status().isNoContent());
        admin(get("/api/v1/mcp-servers/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MCP_SERVER_NOT_FOUND"));
    }

    @Test
    void managementRequiresAdminAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/mcp-servers")).andExpect(status().isUnauthorized());
    }
}
