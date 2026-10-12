package com.agentgate.memory.controller;

import com.agentgate.common.exception.InvalidRuntimeTokenException;
import com.agentgate.memory.dto.AgentMemory;
import com.agentgate.memory.dto.MemorySaveRequest;
import com.agentgate.memory.dto.MemorySearchRequest;
import com.agentgate.memory.service.MemoryService;
import com.agentgate.runtime.RuntimeClient;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agents' memory for the runtime (shared runtime token). Whether an agent may save or search is decided
 * before these calls, when the runtime has AgentGate evaluate the memory action.
 */
@RestController
@RequestMapping("/api/v1/runtime/memories")
@RequiredArgsConstructor
public class RuntimeMemoryController {

    private final MemoryService memoryService;
    private final RuntimeClient runtimeClient;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentMemory save(@RequestHeader(name = "X-Runtime-Token", required = false) String token,
                            @Valid @RequestBody MemorySaveRequest request) {
        requireRuntime(token);
        return memoryService.save(request.agentId(), request.content(), AgentMemory.AGENT, request.executionId(),
                request.agentId());
    }

    @PostMapping("/search")
    public List<AgentMemory> search(@RequestHeader(name = "X-Runtime-Token", required = false) String token,
                                    @Valid @RequestBody MemorySearchRequest request) {
        requireRuntime(token);
        return memoryService.search(request.agentId(), request.query(), request.limitOrDefault());
    }

    private void requireRuntime(String token) {
        if (!runtimeClient.acceptsToken(token)) {
            throw new InvalidRuntimeTokenException();
        }
    }
}
