package com.agentgate.agent.service;

import com.agentgate.agent.domain.Agent;
import com.agentgate.agent.domain.AgentDefinitionVersion;
import com.agentgate.agent.dto.AgentDefinition;
import com.agentgate.agent.dto.AgentDefinitionResponse;
import com.agentgate.agent.dto.AgentToolDefinition;
import com.agentgate.agent.repository.AgentDefinitionVersionRepository;
import com.agentgate.agent.repository.AgentRepository;
import com.agentgate.common.exception.AgentDefinitionNotFoundException;
import com.agentgate.common.exception.AgentNotFoundException;
import com.agentgate.common.exception.InvalidAgentDefinitionException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class AgentDefinitionService {

    private final AgentRepository agentRepository;
    private final AgentDefinitionVersionRepository definitionRepository;
    private final ObjectMapper objectMapper;

    /** Stores the definition as the agent's next version. */
    @Transactional
    public AgentDefinitionResponse save(Long id, AgentDefinition definition) {
        validate(definition);
        Agent agent = agentRepository.findForUpdate(id).orElseThrow(() -> new AgentNotFoundException(id));
        AgentDefinition normalized = definition.normalized();
        int version = agent.nextDefinitionVersion(normalized.description());
        AgentDefinitionVersion saved = definitionRepository.save(
                new AgentDefinitionVersion(agent, version, objectMapper.writeValueAsString(normalized)));
        return new AgentDefinitionResponse(agent.getAgentId(), version, saved.getCreatedAt(), normalized);
    }

    @Transactional(readOnly = true)
    public AgentDefinitionResponse latest(Long id) {
        Agent agent = findOrThrow(id);
        if (agent.getLatestDefinitionVersion() == 0) {
            throw new AgentDefinitionNotFoundException("Agent '%s' has no definition".formatted(agent.getAgentId()));
        }
        return toResponse(findVersion(agent, agent.getLatestDefinitionVersion()));
    }

    @Transactional(readOnly = true)
    public AgentDefinitionResponse version(Long id, int version) {
        return toResponse(findVersion(findOrThrow(id), version));
    }

    /** Version numbers and dates, newest first; definitions omitted. */
    @Transactional(readOnly = true)
    public List<AgentDefinitionResponse> versions(Long id) {
        Agent agent = findOrThrow(id);
        return definitionRepository.findByAgentOrderByVersionDesc(agent).stream()
                .map(v -> new AgentDefinitionResponse(agent.getAgentId(), v.getVersion(), v.getCreatedAt(), null))
                .toList();
    }

    private static void validate(AgentDefinition definition) {
        Set<String> seen = new HashSet<>();
        for (AgentToolDefinition tool : definition.tools()) {
            if (!seen.add(tool.action())) {
                throw new InvalidAgentDefinitionException(
                        "tools: '%s/%s' is listed more than once".formatted(tool.server(), tool.tool()));
            }
        }
        if (definition.outputSchema() != null && !definition.outputSchema().isObject()) {
            throw new InvalidAgentDefinitionException("outputSchema: must be a JSON Schema object");
        }
    }

    private Agent findOrThrow(Long id) {
        return agentRepository.findById(id).orElseThrow(() -> new AgentNotFoundException(id));
    }

    private AgentDefinitionVersion findVersion(Agent agent, int version) {
        return definitionRepository.findByAgentAndVersion(agent, version)
                .orElseThrow(() -> new AgentDefinitionNotFoundException(
                        "Agent '%s' has no definition version %d".formatted(agent.getAgentId(), version)));
    }

    private AgentDefinitionResponse toResponse(AgentDefinitionVersion version) {
        AgentDefinition definition = objectMapper.readValue(version.getDefinition(), AgentDefinition.class);
        return new AgentDefinitionResponse(version.getAgent().getAgentId(), version.getVersion(),
                version.getCreatedAt(), definition);
    }
}
