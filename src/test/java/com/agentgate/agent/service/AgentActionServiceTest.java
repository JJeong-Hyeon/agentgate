package com.agentgate.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentgate.agent.domain.Agent;
import com.agentgate.agent.dto.ActionRequest;
import com.agentgate.agent.dto.ActionResponse;
import com.agentgate.agent.repository.AgentRepository;
import com.agentgate.approval.domain.ApprovalRequest;
import com.agentgate.approval.service.ApprovalService;
import com.agentgate.audit.service.AuditLogService;
import com.agentgate.common.exception.AgentNotFoundException;
import com.agentgate.common.exception.InvalidApiKeyException;
import com.agentgate.common.security.ApiKeyGenerator;
import com.agentgate.risk.ActionStatus;
import com.agentgate.risk.RiskEvaluationResult;
import com.agentgate.risk.RiskEvaluationService;
import com.agentgate.risk.RiskLevel;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentActionServiceTest {

    private static final String API_KEY = "test-key";

    @Mock
    private AgentRepository agentRepository;

    @Mock
    private RiskEvaluationService riskEvaluationService;

    @Mock
    private ApprovalService approvalService;

    @Mock
    private AuditLogService auditLogService;

    private AgentActionService service;

    @BeforeEach
    void setUp() {
        service = new AgentActionService(agentRepository, riskEvaluationService, approvalService, auditLogService);
    }

    @Test
    void throwsWhenAgentIsUnknown() {
        when(agentRepository.findByAgentId("ghost-agent")).thenReturn(Optional.empty());

        ActionRequest request = new ActionRequest("ghost-agent", "VIEW_DATA", null, List.of());

        assertThatThrownBy(() -> service.evaluate(request, API_KEY))
                .isInstanceOf(AgentNotFoundException.class);
    }

    @Test
    void throwsWhenApiKeyIsWrong() {
        when(agentRepository.findByAgentId("mail-agent"))
                .thenReturn(Optional.of(new Agent("mail-agent", "Mail Agent", ApiKeyGenerator.hash(API_KEY))));

        ActionRequest request = new ActionRequest("mail-agent", "VIEW_DATA", null, List.of());

        assertThatThrownBy(() -> service.evaluate(request, "wrong-key"))
                .isInstanceOf(InvalidApiKeyException.class);
    }

    @Test
    void returnsEvaluationResultWhenAgentExists() {
        when(agentRepository.findByAgentId("mail-agent"))
                .thenReturn(Optional.of(new Agent("mail-agent", "Mail Agent", ApiKeyGenerator.hash(API_KEY))));
        when(riskEvaluationService.evaluate("VIEW_DATA", List.of()))
                .thenReturn(new RiskEvaluationResult(RiskLevel.LOW, ActionStatus.ALLOWED));

        ActionRequest request = new ActionRequest("mail-agent", "VIEW_DATA", null, List.of());

        ActionResponse response = service.evaluate(request, API_KEY);

        assertThat(response.status()).isEqualTo(ActionStatus.ALLOWED);
        assertThat(response.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(response.approvalId()).isNull();
        verify(approvalService, never()).createRequest(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void createsApprovalRequestWhenApprovalIsRequired() {
        when(agentRepository.findByAgentId("mail-agent"))
                .thenReturn(Optional.of(new Agent("mail-agent", "Mail Agent", ApiKeyGenerator.hash(API_KEY))));
        when(riskEvaluationService.evaluate("SEND_EMAIL", List.of("PII")))
                .thenReturn(new RiskEvaluationResult(RiskLevel.HIGH, ActionStatus.APPROVAL_REQUIRED));
        ApprovalRequest created = new ApprovalRequest("mail-agent", "SEND_EMAIL", null, List.of("PII"), RiskLevel.HIGH);
        setId(created, 42L);
        when(approvalService.createRequest(eq("mail-agent"), eq("SEND_EMAIL"), any(), eq(List.of("PII")), eq(RiskLevel.HIGH)))
                .thenReturn(created);

        ActionRequest request = new ActionRequest("mail-agent", "SEND_EMAIL", null, List.of("PII"));

        ActionResponse response = service.evaluate(request, API_KEY);

        assertThat(response.status()).isEqualTo(ActionStatus.APPROVAL_REQUIRED);
        assertThat(response.approvalId()).isEqualTo(42L);
    }

    @Test
    void overridesToBlockedWhenExceedingAgentRiskCap() {
        Agent agent = new Agent("mail-agent", "Mail Agent", ApiKeyGenerator.hash(API_KEY));
        agent.restrictTo(RiskLevel.LOW);
        when(agentRepository.findByAgentId("mail-agent")).thenReturn(Optional.of(agent));
        when(riskEvaluationService.evaluate("SEND_EMAIL", List.of("PII")))
                .thenReturn(new RiskEvaluationResult(RiskLevel.HIGH, ActionStatus.APPROVAL_REQUIRED));

        ActionRequest request = new ActionRequest("mail-agent", "SEND_EMAIL", null, List.of("PII"));

        ActionResponse response = service.evaluate(request, API_KEY);

        assertThat(response.status()).isEqualTo(ActionStatus.BLOCKED);
        assertThat(response.riskLevel()).isEqualTo(RiskLevel.BLOCKED);
        assertThat(response.approvalId()).isNull();
        verify(approvalService, never()).createRequest(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void allowsWhenWithinAgentRiskCap() {
        Agent agent = new Agent("mail-agent", "Mail Agent", ApiKeyGenerator.hash(API_KEY));
        agent.restrictTo(RiskLevel.HIGH);
        when(agentRepository.findByAgentId("mail-agent")).thenReturn(Optional.of(agent));
        when(riskEvaluationService.evaluate("VIEW_DATA", List.of()))
                .thenReturn(new RiskEvaluationResult(RiskLevel.LOW, ActionStatus.ALLOWED));

        ActionRequest request = new ActionRequest("mail-agent", "VIEW_DATA", null, List.of());

        ActionResponse response = service.evaluate(request, API_KEY);

        assertThat(response.status()).isEqualTo(ActionStatus.ALLOWED);
        assertThat(response.riskLevel()).isEqualTo(RiskLevel.LOW);
    }

    private static void setId(ApprovalRequest approvalRequest, Long id) {
        try {
            Field field = ApprovalRequest.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(approvalRequest, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
