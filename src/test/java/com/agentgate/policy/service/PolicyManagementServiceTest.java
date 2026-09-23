package com.agentgate.policy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentgate.common.exception.PolicyNotFoundException;
import com.agentgate.policy.domain.Policy;
import com.agentgate.policy.dto.PolicyRequest;
import com.agentgate.policy.dto.PolicyResponse;
import com.agentgate.policy.repository.PolicyRepository;
import com.agentgate.risk.RiskLevel;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PolicyManagementServiceTest {

    @Mock
    private PolicyRepository policyRepository;

    private PolicyManagementService service;

    @BeforeEach
    void setUp() {
        service = new PolicyManagementService(policyRepository);
    }

    @Test
    void createsPolicy() {
        when(policyRepository.save(any(Policy.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PolicyResponse response = service.create(new PolicyRequest("VIEW_DATA", null, RiskLevel.LOW));

        assertThat(response.actionType()).isEqualTo("VIEW_DATA");
        assertThat(response.riskLevel()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void listsAllPolicies() {
        when(policyRepository.findAll()).thenReturn(List.of(new Policy("VIEW_DATA", null, RiskLevel.LOW)));

        List<PolicyResponse> result = service.list();

        assertThat(result).hasSize(1);
    }

    @Test
    void getThrowsWhenPolicyMissing() {
        when(policyRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(99L)).isInstanceOf(PolicyNotFoundException.class);
    }

    @Test
    void updatesExistingPolicy() {
        Policy existing = new Policy("VIEW_DATA", null, RiskLevel.LOW);
        when(policyRepository.findById(1L)).thenReturn(Optional.of(existing));

        PolicyResponse response = service.update(1L, new PolicyRequest("VIEW_DATA", "PII", RiskLevel.HIGH));

        assertThat(response.label()).isEqualTo("PII");
        assertThat(response.riskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void deletesExistingPolicy() {
        when(policyRepository.existsById(1L)).thenReturn(true);

        service.delete(1L);

        verify(policyRepository).deleteById(1L);
    }

    @Test
    void deleteThrowsWhenPolicyMissing() {
        when(policyRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(99L)).isInstanceOf(PolicyNotFoundException.class);
    }
}
