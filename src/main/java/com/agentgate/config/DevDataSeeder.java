package com.agentgate.config;

import com.agentgate.agent.domain.Agent;
import com.agentgate.agent.repository.AgentRepository;
import com.agentgate.policy.domain.Policy;
import com.agentgate.policy.repository.PolicyRepository;
import com.agentgate.risk.RiskLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@RequiredArgsConstructor
public class DevDataSeeder implements CommandLineRunner {

    private final AgentRepository agentRepository;
    private final PolicyRepository policyRepository;

    @Override
    public void run(String... args) {
        if (policyRepository.count() > 0) {
            return;
        }

        agentRepository.save(new Agent("mail-agent", "Mail Agent"));

        policyRepository.save(new Policy(null, "PII", RiskLevel.HIGH));
        policyRepository.save(new Policy("VIEW_DATA", null, RiskLevel.LOW));
        policyRepository.save(new Policy("DELETE_DATA", null, RiskLevel.BLOCKED));
    }
}
