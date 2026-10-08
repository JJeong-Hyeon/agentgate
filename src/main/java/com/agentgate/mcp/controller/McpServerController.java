package com.agentgate.mcp.controller;

import com.agentgate.mcp.dto.McpServerRequest;
import com.agentgate.mcp.dto.McpServerResponse;
import com.agentgate.mcp.service.McpServerService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** MCP servers registered in AgentGate (Streamable HTTP only; stdio servers stay in the runtime's config file). */
@RestController
@RequestMapping("/api/v1/mcp-servers")
@RequiredArgsConstructor
public class McpServerController {

    private final McpServerService service;

    @PostMapping
    public ResponseEntity<McpServerResponse> create(@Valid @RequestBody McpServerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @GetMapping
    public ResponseEntity<List<McpServerResponse>> list() {
        return ResponseEntity.ok(service.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<McpServerResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(service.get(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<McpServerResponse> update(@PathVariable Long id, @Valid @RequestBody McpServerRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
