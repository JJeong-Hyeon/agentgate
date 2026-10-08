package com.agentgate.agent.controller;

import com.agentgate.agent.dto.AgentDefinition;
import com.agentgate.agent.dto.AgentDefinitionResponse;
import com.agentgate.agent.service.AgentDefinitionService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agents/{id}/definition")
@RequiredArgsConstructor
public class AgentDefinitionController {

    private final AgentDefinitionService agentDefinitionService;

    @PutMapping
    public ResponseEntity<AgentDefinitionResponse> save(@PathVariable Long id,
                                                        @Valid @RequestBody AgentDefinition definition) {
        return ResponseEntity.status(HttpStatus.CREATED).body(agentDefinitionService.save(id, definition));
    }

    @GetMapping
    public ResponseEntity<AgentDefinitionResponse> latest(@PathVariable Long id) {
        return ResponseEntity.ok(agentDefinitionService.latest(id));
    }

    @GetMapping("/versions")
    public ResponseEntity<List<AgentDefinitionResponse>> versions(@PathVariable Long id) {
        return ResponseEntity.ok(agentDefinitionService.versions(id));
    }

    @GetMapping("/versions/{version}")
    public ResponseEntity<AgentDefinitionResponse> version(@PathVariable Long id, @PathVariable int version) {
        return ResponseEntity.ok(agentDefinitionService.version(id, version));
    }
}
