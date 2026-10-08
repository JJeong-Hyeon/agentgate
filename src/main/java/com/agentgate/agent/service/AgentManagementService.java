package com.agentgate.agent.service;

import com.agentgate.agent.domain.Agent;
import com.agentgate.agent.dto.AgentCreateRequest;
import com.agentgate.agent.dto.AgentCreateResponse;
import com.agentgate.agent.dto.AgentResponse;
import com.agentgate.agent.repository.AgentRepository;
import com.agentgate.common.exception.AgentNotFoundException;
import com.agentgate.common.exception.DuplicateAgentException;
import com.agentgate.common.security.ApiKeyGenerator;
import com.agentgate.risk.RiskLevel;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AgentManagementService {

    private final AgentRepository agentRepository;

    @Transactional
    public AgentCreateResponse create(AgentCreateRequest request) {
        if (agentRepository.findByAgentId(request.agentId()).isPresent()) {
            throw new DuplicateAgentException(request.agentId());
        }
        String apiKey = ApiKeyGenerator.generate();
        Agent agent = new Agent(request.agentId(), request.name(), ApiKeyGenerator.hash(apiKey));
        Agent saved;
        try {
            // Flushed here so a concurrent registration of the same id fails as a duplicate, not later.
            saved = agentRepository.saveAndFlush(agent);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateAgentException(request.agentId());
        }
        return new AgentCreateResponse(saved.getId(), saved.getAgentId(), saved.getName(), apiKey, saved.getCreatedAt());
    }

    public List<AgentResponse> list() {
        return agentRepository.findAll().stream().map(AgentResponse::from).toList();
    }

    public AgentResponse get(Long id) {
        return agentRepository.findById(id)
                .map(AgentResponse::from)
                .orElseThrow(() -> new AgentNotFoundException(id));
    }

    @Transactional
    public AgentResponse restrict(Long id, RiskLevel maxRiskLevel) {
        Agent agent = agentRepository.findById(id).orElseThrow(() -> new AgentNotFoundException(id));
        agent.restrictTo(maxRiskLevel);
        return AgentResponse.from(agent);
    }
}
