package com.agentgate.config;

import com.agentgate.agent.domain.Agent;
import com.agentgate.agent.repository.AgentRepository;
import com.agentgate.common.security.ApiKeyGenerator;
import com.agentgate.policy.domain.Policy;
import com.agentgate.policy.repository.PolicyRepository;
import com.agentgate.risk.RiskLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@RequiredArgsConstructor
public class DevDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final AgentRepository agentRepository;
    private final PolicyRepository policyRepository;

    @Override
    public void run(String... args) {
        if (policyRepository.count() > 0) {
            return;
        }

        String apiKey = ApiKeyGenerator.generate();
        agentRepository.save(new Agent("mail-agent", "Mail Agent", ApiKeyGenerator.hash(apiKey)));
        log.info("Seeded dev agent 'mail-agent' with API key: {}", apiKey);

        policyRepository.save(new Policy(null, "PII", RiskLevel.HIGH));
        policyRepository.save(new Policy("VIEW_DATA", null, RiskLevel.LOW));
        policyRepository.save(new Policy("DELETE_DATA", null, RiskLevel.BLOCKED));
    }
}
