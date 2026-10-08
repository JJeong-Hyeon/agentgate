package com.agentgate.mcp.controller;

import com.agentgate.common.exception.InvalidRuntimeTokenException;
import com.agentgate.mcp.dto.RuntimeMcpServer;
import com.agentgate.mcp.service.McpServerService;
import com.agentgate.runtime.RuntimeClient;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** MCP servers with their credentials, for the agent runtime (shared runtime token). */
@RestController
@RequestMapping("/api/v1/runtime/mcp-servers")
@RequiredArgsConstructor
public class RuntimeMcpServerController {

    private final McpServerService service;
    private final RuntimeClient runtimeClient;

    @GetMapping
    public ResponseEntity<List<RuntimeMcpServer>> list(
            @RequestHeader(name = "X-Runtime-Token", required = false) String token) {
        if (!runtimeClient.acceptsToken(token)) {
            throw new InvalidRuntimeTokenException();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.forRuntime());
    }
}
