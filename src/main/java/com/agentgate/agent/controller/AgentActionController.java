package com.agentgate.agent.controller;

import com.agentgate.agent.dto.ActionRequest;
import com.agentgate.agent.dto.ActionResponse;
import com.agentgate.agent.service.AgentActionService;
import com.agentgate.common.exception.InvalidApiKeyException;
import com.agentgate.runtime.RuntimeClient;
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
    private final RuntimeClient runtimeClient;

    /**
     * Agents authenticate with their own API key; the runtime authenticates with the shared
     * runtime token and evaluates on behalf of the agent it is running.
     */
    @PostMapping
    public ResponseEntity<ActionResponse> submitAction(@Valid @RequestBody ActionRequest request,
                                                       @RequestHeader(name = "X-API-Key", required = false) String apiKey,
                                                       @RequestHeader(name = "X-Runtime-Token", required = false) String runtimeToken) {
        boolean trusted = runtimeToken != null && runtimeClient.acceptsToken(runtimeToken);
        if (!trusted && apiKey == null) {
            throw new InvalidApiKeyException();
        }
        return ResponseEntity.ok(agentActionService.evaluate(request, apiKey, trusted));
    }
}
