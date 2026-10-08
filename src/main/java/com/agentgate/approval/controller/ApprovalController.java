package com.agentgate.approval.controller;

import com.agentgate.approval.domain.ApprovalStatus;
import com.agentgate.approval.dto.ApprovalDecisionRequest;
import com.agentgate.approval.dto.ApprovalResponse;
import com.agentgate.approval.service.ApprovalService;
import java.security.Principal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/approvals")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService approvalService;

    // The decider is recorded as the signed-in user; a decidedBy in the body is accepted but ignored.
    @PostMapping("/{id}/approve")
    public ResponseEntity<ApprovalResponse> approve(@PathVariable Long id,
                                                      @RequestBody(required = false) ApprovalDecisionRequest request,
                                                      Principal principal) {
        return ResponseEntity.ok(approvalService.approve(id, principal.getName()));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<ApprovalResponse> reject(@PathVariable Long id,
                                                     @RequestBody(required = false) ApprovalDecisionRequest request,
                                                     Principal principal) {
        return ResponseEntity.ok(approvalService.reject(id, principal.getName()));
    }

    @GetMapping
    public ResponseEntity<List<ApprovalResponse>> list(@RequestParam(required = false) ApprovalStatus status,
                                                       @RequestParam(required = false) String executionId) {
        return ResponseEntity.ok(approvalService.list(status, executionId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApprovalResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(approvalService.get(id));
    }
}
