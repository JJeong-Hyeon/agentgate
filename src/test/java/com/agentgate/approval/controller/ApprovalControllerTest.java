package com.agentgate.approval.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentgate.approval.domain.ApprovalStatus;
import com.agentgate.approval.dto.ApprovalResponse;
import com.agentgate.approval.service.ApprovalService;
import com.agentgate.common.exception.ApprovalNotFoundException;
import com.agentgate.common.exception.IllegalApprovalStateException;
import com.agentgate.risk.RiskLevel;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ApprovalController.class)
@AutoConfigureMockMvc(addFilters = false)
class ApprovalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApprovalService approvalService;

    @Test
    void approveReturnsOk() throws Exception {
        when(approvalService.approve(eq(1L), any()))
                .thenReturn(new ApprovalResponse(1L, "mail-agent", "SEND_EMAIL", null, List.of("PII"),
                        RiskLevel.HIGH, ApprovalStatus.APPROVED, Instant.now(), Instant.now(), "alice", null));

        mockMvc.perform(post("/api/v1/approvals/1/approve").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void approveReturnsNotFoundWhenMissing() throws Exception {
        when(approvalService.approve(eq(99L), any())).thenThrow(new ApprovalNotFoundException(99L));

        mockMvc.perform(post("/api/v1/approvals/99/approve").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPROVAL_NOT_FOUND"));
    }

    @Test
    void approveReturnsConflictWhenAlreadyDecided() throws Exception {
        when(approvalService.approve(eq(1L), any())).thenThrow(new IllegalApprovalStateException(1L, ApprovalStatus.APPROVED));

        mockMvc.perform(post("/api/v1/approvals/1/approve").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_APPROVAL_STATE"));
    }

    @Test
    void listReturnsApprovals() throws Exception {
        when(approvalService.list(null, null)).thenReturn(List.of(
                new ApprovalResponse(1L, "mail-agent", "SEND_EMAIL", null, List.of("PII"),
                        RiskLevel.HIGH, ApprovalStatus.PENDING, Instant.now(), null, null, null)));

        mockMvc.perform(get("/api/v1/approvals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }
}
