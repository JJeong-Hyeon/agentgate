package com.agentgate.policy.controller;

import com.agentgate.policy.dto.PolicyRequest;
import com.agentgate.policy.dto.PolicyResponse;
import com.agentgate.policy.service.PolicyManagementService;
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

@RestController
@RequestMapping("/api/v1/policies")
@RequiredArgsConstructor
public class PolicyController {

    private final PolicyManagementService policyManagementService;

    @PostMapping
    public ResponseEntity<PolicyResponse> create(@Valid @RequestBody PolicyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(policyManagementService.create(request));
    }

    @GetMapping
    public ResponseEntity<List<PolicyResponse>> list() {
        return ResponseEntity.ok(policyManagementService.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PolicyResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(policyManagementService.get(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PolicyResponse> update(@PathVariable Long id, @Valid @RequestBody PolicyRequest request) {
        return ResponseEntity.ok(policyManagementService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        policyManagementService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
