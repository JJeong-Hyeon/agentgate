package com.agentgate.workflow.controller;

import com.agentgate.workflow.dto.WorkflowCreateRequest;
import com.agentgate.workflow.dto.WorkflowResponse;
import com.agentgate.workflow.dto.WorkflowVersionRequest;
import com.agentgate.workflow.dto.WorkflowVersionResponse;
import com.agentgate.workflow.service.WorkflowService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;

    @PostMapping
    public ResponseEntity<WorkflowResponse> create(@Valid @RequestBody WorkflowCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(workflowService.create(request));
    }

    @GetMapping
    public ResponseEntity<List<WorkflowResponse>> list() {
        return ResponseEntity.ok(workflowService.list());
    }

    @GetMapping("/{workflowId}")
    public ResponseEntity<WorkflowResponse> get(@PathVariable String workflowId) {
        return ResponseEntity.ok(workflowService.get(workflowId));
    }

    @PostMapping("/{workflowId}/versions")
    public ResponseEntity<WorkflowVersionResponse> addVersion(@PathVariable String workflowId,
                                                              @Valid @RequestBody WorkflowVersionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(workflowService.addVersion(workflowId, request.dsl()));
    }

    @GetMapping("/{workflowId}/versions")
    public ResponseEntity<List<WorkflowVersionResponse>> versions(@PathVariable String workflowId) {
        return ResponseEntity.ok(workflowService.versions(workflowId));
    }

    @GetMapping("/{workflowId}/versions/{version}")
    public ResponseEntity<WorkflowVersionResponse> version(@PathVariable String workflowId, @PathVariable int version) {
        return ResponseEntity.ok(workflowService.version(workflowId, version));
    }
}
