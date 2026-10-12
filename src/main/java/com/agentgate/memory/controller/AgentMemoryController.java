package com.agentgate.memory.controller;

import com.agentgate.memory.dto.AgentMemory;
import com.agentgate.memory.dto.MemoryCreateRequest;
import com.agentgate.memory.service.MemoryService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** An agent's memory in the console: browse and search (any role), add and forget (ADMIN). */
@RestController
@RequestMapping("/api/v1/agents/{agentId}/memories")
@RequiredArgsConstructor
public class AgentMemoryController {

    private final MemoryService memoryService;

    @GetMapping
    public List<AgentMemory> list(@PathVariable String agentId, @RequestParam(required = false) String query) {
        return memoryService.list(agentId, query);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentMemory create(@PathVariable String agentId, @Valid @RequestBody MemoryCreateRequest request,
                              Principal principal) {
        return memoryService.save(agentId, request.content(), AgentMemory.USER, null, principal.getName());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String agentId, @PathVariable Long id) {
        memoryService.delete(agentId, id);
    }
}
