package com.agentgate.tool;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentgate.runtime.McpServerTools;
import com.agentgate.runtime.McpToolInfo;
import com.agentgate.runtime.RuntimeClient;
import com.agentgate.runtime.RuntimeUnavailableException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(ToolController.class)
@AutoConfigureMockMvc(addFilters = false)
class ToolControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RuntimeClient runtimeClient;

    @Test
    void listsToolsInCamelCase() throws Exception {
        McpToolInfo tool = new McpToolInfo("save_note", null, "Save a note.",
                new JsonMapper().readTree("{\"type\":\"object\"}"), null);
        when(runtimeClient.listTools(true)).thenReturn(List.of(new McpServerTools("notes", "url", "agentgate", List.of(tool), null)));

        mockMvc.perform(get("/api/v1/tools").param("refresh", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].server").value("notes"))
                .andExpect(jsonPath("$[0].source").value("agentgate"))
                .andExpect(jsonPath("$[0].tools[0].inputSchema.type").value("object"));
    }

    @Test
    void returnsServiceUnavailableWhenRuntimeIsDown() throws Exception {
        when(runtimeClient.listTools(false)).thenThrow(new RuntimeUnavailableException("down"));

        mockMvc.perform(get("/api/v1/tools"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RUNTIME_UNAVAILABLE"));
    }
}
