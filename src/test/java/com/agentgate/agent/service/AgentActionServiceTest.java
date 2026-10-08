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
import com.agentgate.agent.dto.AgentDefinition;
import com.agentgate.agent.dto.AgentToolDefinition;
import com.agentgate.agent.domain.ToolPermission;
import com.agentgate.agent.repository.AgentRepository;
import com.agentgate.approval.domain.ApprovalRequest;
import com.agentgate.approval.service.ApprovalService;
import com.agentgate.audit.service.AuditLogService;
import com.agentgate.common.exception.AgentNotFoundException;
import com.agentgate.common.exception.InvalidApiKeyException;
import com.agentgate.common.security.ApiKeyGenerator;
import com.agentgate.risk.ActionStatus;
import com.agentgate.risk.DecisionBasis;
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
    private AgentDefinitionService agentDefinitionService;

    @Mock
    private RiskEvaluationService riskEvaluationService;

    @Mock
    private ApprovalService approvalService;

    @Mock
    private AuditLogService auditLogService;

    private AgentActionService service;

    @BeforeEach
    void setUp() {
        service = new AgentActionService(agentRepository, agentDefinitionService, riskEvaluationService, approvalService, auditLogService);
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
        verify(approvalService, never()).createRequest(anyString(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void createsApprovalRequestWhenApprovalIsRequired() {
        when(agentRepository.findByAgentId("mail-agent"))
                .thenReturn(Optional.of(new Agent("mail-agent", "Mail Agent", ApiKeyGenerator.hash(API_KEY))));
        when(riskEvaluationService.evaluate("SEND_EMAIL", List.of("PII")))
                .thenReturn(new RiskEvaluationResult(RiskLevel.HIGH, ActionStatus.APPROVAL_REQUIRED));
        ApprovalRequest created = new ApprovalRequest("mail-agent", "SEND_EMAIL", null, List.of("PII"), RiskLevel.HIGH);
        setId(created, 42L);
        when(approvalService.createRequest(eq("mail-agent"), eq("SEND_EMAIL"), any(), eq(List.of("PII")), eq(RiskLevel.HIGH), any(), any()))
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
        verify(approvalService, never()).createRequest(anyString(), anyString(), any(), any(), any(), any(), any());
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

    @Test
    void runtimeMayEvaluateWithoutTheAgentsApiKey() {
        when(agentRepository.findByAgentId("mail-agent"))
                .thenReturn(Optional.of(new Agent("mail-agent", "Mail Agent", ApiKeyGenerator.hash(API_KEY))));
        when(riskEvaluationService.evaluate("VIEW_DATA", List.of()))
                .thenReturn(new RiskEvaluationResult(RiskLevel.LOW, ActionStatus.ALLOWED));

        ActionResponse response = service.evaluate(
                new ActionRequest("mail-agent", "VIEW_DATA", null, List.of()), null, true);

        assertThat(response.status()).isEqualTo(ActionStatus.ALLOWED);
        assertThat(response.basis()).isEqualTo(DecisionBasis.POLICY);
    }

    @Test
    void blocksToolsNotInTheDefinition() {
        Agent agent = agentWithTools(new AgentToolDefinition("notes", "list_notes", ToolPermission.AUTO, List.of()));

        ActionResponse response = service.evaluate(toolCall(agent, "MCP:notes:delete_note"), null, true);

        assertThat(response.status()).isEqualTo(ActionStatus.BLOCKED);
        assertThat(response.basis()).isEqualTo(DecisionBasis.TOOL_NOT_GRANTED);
        verify(riskEvaluationService, never()).evaluate(anyString(), any());
        verify(auditLogService).record(eq("mail-agent"), eq("MCP:notes:delete_note"), any(), any(),
                eq(RiskLevel.BLOCKED), eq(ActionStatus.BLOCKED), any(), eq(DecisionBasis.TOOL_NOT_GRANTED));
    }

    @Test
    void blocksToolsMarkedBlockedWhateverPoliciesSay() {
        Agent agent = agentWithTools(new AgentToolDefinition("notes", "delete_note", ToolPermission.BLOCKED, List.of()));

        ActionResponse response = service.evaluate(toolCall(agent, "MCP:notes:delete_note"), null, true);

        assertThat(response.status()).isEqualTo(ActionStatus.BLOCKED);
        assertThat(response.basis()).isEqualTo(DecisionBasis.TOOL_BLOCKED);
    }

    @Test
    void requiresApprovalForApprovalToolsEvenWhenPoliciesAllow() {
        Agent agent = agentWithTools(new AgentToolDefinition("notes", "save_note", ToolPermission.APPROVAL, List.of()));
        when(riskEvaluationService.evaluate("MCP:notes:save_note", List.of()))
                .thenReturn(new RiskEvaluationResult(RiskLevel.LOW, ActionStatus.ALLOWED));
        ApprovalRequest created = new ApprovalRequest("mail-agent", "MCP:notes:save_note", null, List.of(), RiskLevel.LOW);
        setId(created, 7L);
        when(approvalService.createRequest(any(), any(), any(), any(), any(), any(), any())).thenReturn(created);

        ActionResponse response = service.evaluate(toolCall(agent, "MCP:notes:save_note"), null, true);

        assertThat(response.status()).isEqualTo(ActionStatus.APPROVAL_REQUIRED);
        assertThat(response.basis()).isEqualTo(DecisionBasis.TOOL_REQUIRES_APPROVAL);
        assertThat(response.approvalId()).isEqualTo(7L);
    }

    @Test
    void autoToolsFollowPoliciesWithToolLabelsMerged() {
        Agent agent = agentWithTools(new AgentToolDefinition("crm", "export", ToolPermission.AUTO, List.of("PII")));
        when(riskEvaluationService.evaluate("MCP:crm:export", List.of("EXTERNAL", "PII")))
                .thenReturn(new RiskEvaluationResult(RiskLevel.BLOCKED, ActionStatus.BLOCKED));

        ActionRequest request = new ActionRequest("mail-agent", "MCP:crm:export", null, List.of("EXTERNAL"),
                null, null, null, 2);
        ActionResponse response = service.evaluate(request, null, true);

        assertThat(response.status()).isEqualTo(ActionStatus.BLOCKED);
        assertThat(response.basis()).isEqualTo(DecisionBasis.POLICY);
    }

    @Test
    void approvalToolsStillHonourTheAgentRiskCap() {
        Agent agent = agentWithTools(new AgentToolDefinition("notes", "save_note", ToolPermission.APPROVAL, List.of()));
        agent.restrictTo(RiskLevel.LOW);
        when(riskEvaluationService.evaluate("MCP:notes:save_note", List.of()))
                .thenReturn(new RiskEvaluationResult(RiskLevel.MEDIUM, ActionStatus.ALLOWED));

        ActionResponse response = service.evaluate(toolCall(agent, "MCP:notes:save_note"), null, true);

        assertThat(response.status()).isEqualTo(ActionStatus.BLOCKED);
        assertThat(response.basis()).isEqualTo(DecisionBasis.AGENT_RISK_CAP);
    }

    private Agent agentWithTools(AgentToolDefinition... tools) {
        Agent agent = new Agent("mail-agent", "Mail Agent", ApiKeyGenerator.hash(API_KEY));
        when(agentRepository.findByAgentId("mail-agent")).thenReturn(Optional.of(agent));
        when(agentDefinitionService.definition(agent, 2)).thenReturn(
                new AgentDefinition(null, null, null, "prompt", List.of(tools), 8, null, null));
        return agent;
    }

    private static ActionRequest toolCall(Agent agent, String action) {
        return new ActionRequest(agent.getAgentId(), action, null, List.of(), "exec-1", null, null, 2);
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
