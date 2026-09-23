package com.agentgate.audit.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentgate.audit.dto.AuditLogResponse;
import com.agentgate.audit.service.AuditLogService;
import com.agentgate.common.exception.AuditLogNotFoundException;
import com.agentgate.risk.ActionStatus;
import com.agentgate.risk.RiskLevel;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuditLogController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuditLogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditLogService auditLogService;

    @Test
    void listReturnsAuditLogs() throws Exception {
        when(auditLogService.list(isNull(), isNull(), isNull())).thenReturn(List.of(
                new AuditLogResponse(1L, "mail-agent", "VIEW_DATA", null, List.of(),
                        RiskLevel.LOW, ActionStatus.ALLOWED, null, Instant.now())));

        mockMvc.perform(get("/api/v1/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].agentId").value("mail-agent"));
    }

    @Test
    void getReturnsNotFoundWhenMissing() throws Exception {
        when(auditLogService.get(eq(99L))).thenThrow(new AuditLogNotFoundException(99L));

        mockMvc.perform(get("/api/v1/audit-logs/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUDIT_LOG_NOT_FOUND"));
    }

    @Test
    void getReturnsAuditLog() throws Exception {
        when(auditLogService.get(eq(1L))).thenReturn(
                new AuditLogResponse(1L, "mail-agent", "SEND_EMAIL", null, List.of("PII"),
                        RiskLevel.HIGH, ActionStatus.APPROVAL_REQUIRED, 5L, Instant.now()));

        mockMvc.perform(get("/api/v1/audit-logs/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalId").value(5));
    }
}
