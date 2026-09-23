package com.agentgate.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.agentgate.policy.repository.PolicyRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RiskEvaluationServiceTest {

    @Mock
    private PolicyRepository policyRepository;

    @Test
    void defaultsToHighRiskAndApprovalRequiredWhenNoPolicyMatches() {
        when(policyRepository.findAll()).thenReturn(List.of());

        RiskEvaluationService service = new RiskEvaluationService(policyRepository);
        RiskEvaluationResult result = service.evaluate("UNKNOWN_ACTION", List.of());

        assertThat(result.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.status()).isEqualTo(ActionStatus.APPROVAL_REQUIRED);
    }
}
