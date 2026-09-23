package com.agentgate.agent.controller;

import com.agentgate.agent.dto.ActionRequest;
import com.agentgate.agent.dto.ActionResponse;
import com.agentgate.agent.service.AgentActionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/actions")
@RequiredArgsConstructor
public class AgentActionController {

    private final AgentActionService agentActionService;

    @PostMapping
    public ResponseEntity<ActionResponse> submitAction(@Valid @RequestBody ActionRequest request,
                                                         @RequestHeader("X-API-Key") String apiKey) {
        return ResponseEntity.ok(agentActionService.evaluate(request, apiKey));
    }
}
