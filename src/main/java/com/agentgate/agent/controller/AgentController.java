package com.agentgate.agent.controller;

import com.agentgate.agent.dto.AgentCreateRequest;
import com.agentgate.agent.dto.AgentCreateResponse;
import com.agentgate.agent.dto.AgentRestrictionRequest;
import com.agentgate.agent.dto.AgentResponse;
import com.agentgate.agent.service.AgentManagementService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentManagementService agentManagementService;

    @PostMapping
    public ResponseEntity<AgentCreateResponse> create(@Valid @RequestBody AgentCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(agentManagementService.create(request));
    }

    @GetMapping
    public ResponseEntity<List<AgentResponse>> list() {
        return ResponseEntity.ok(agentManagementService.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<AgentResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(agentManagementService.get(id));
    }

    @PutMapping("/{id}/max-risk-level")
    public ResponseEntity<AgentResponse> restrict(@PathVariable Long id, @RequestBody AgentRestrictionRequest request) {
        return ResponseEntity.ok(agentManagementService.restrict(id, request.maxRiskLevel()));
    }
}
