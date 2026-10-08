package com.agentgate.tool;

import com.agentgate.runtime.McpServerTools;
import com.agentgate.runtime.RuntimeClient;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Tools agents can be given: what the runtime's MCP servers offer. */
@RestController
@RequestMapping("/api/v1/tools")
@RequiredArgsConstructor
public class ToolController {

    private final RuntimeClient runtimeClient;

    @GetMapping
    public ResponseEntity<List<McpServerTools>> list(@RequestParam(defaultValue = "false") boolean refresh) {
        return ResponseEntity.ok(runtimeClient.listTools(refresh));
    }
}
