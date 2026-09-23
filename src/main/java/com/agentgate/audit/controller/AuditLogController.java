package com.agentgate.audit.controller;

import com.agentgate.audit.dto.AuditLogResponse;
import com.agentgate.audit.service.AuditLogService;
import com.agentgate.risk.ActionStatus;
import com.agentgate.risk.RiskLevel;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping
    public ResponseEntity<List<AuditLogResponse>> list(@RequestParam(required = false) String agentId,
                                                         @RequestParam(required = false) ActionStatus status,
                                                         @RequestParam(required = false) RiskLevel riskLevel) {
        return ResponseEntity.ok(auditLogService.list(agentId, status, riskLevel));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AuditLogResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(auditLogService.get(id));
    }
}
