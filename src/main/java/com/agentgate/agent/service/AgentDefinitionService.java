package com.agentgate.agent.service;

import com.agentgate.agent.domain.Agent;
import com.agentgate.agent.domain.AgentDefinitionVersion;
import com.agentgate.agent.dto.AgentDefinition;
import com.agentgate.agent.dto.AgentDefinitionResponse;
import com.agentgate.agent.dto.AgentDelegateDefinition;
import com.agentgate.agent.dto.AgentToolDefinition;
import com.agentgate.agent.repository.AgentDefinitionVersionRepository;
import com.agentgate.agent.repository.AgentRepository;
import com.agentgate.common.exception.AgentDefinitionNotFoundException;
import com.agentgate.common.exception.AgentNotFoundException;
import com.agentgate.common.exception.InvalidAgentDefinitionException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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

    /** How many delegation hops a chain may have (supervisor → worker → sub-worker → …). */
    public static final int MAX_DELEGATION_DEPTH = 3;

    /** Stores the definition as the agent's next version. */
    @Transactional
    public AgentDefinitionResponse save(Long id, AgentDefinition definition) {
        validate(definition);
        Agent agent = agentRepository.findForUpdate(id).orElseThrow(() -> new AgentNotFoundException(id));
        validateDelegates(agent.getAgentId(), definition.normalized().delegates());
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

    /** The given definition version of an agent, for evaluating its tool calls. */
    @Transactional(readOnly = true)
    public AgentDefinition definition(Agent agent, int version) {
        return objectMapper.readValue(findVersion(agent, version).getDefinition(), AgentDefinition.class);
    }

    /** The latest definition of the agent with this id, if it exists and has one. */
    @Transactional(readOnly = true)
    public Optional<AgentDefinition> latestOf(String agentId) {
        return agentRepository.findByAgentId(agentId)
                .filter(a -> a.getLatestDefinitionVersion() > 0)
                .map(a -> definition(a, a.getLatestDefinitionVersion()));
    }

    /**
     * Delegates must be other agents that have a definition, and the delegation graph (with this
     * definition in place of the agent's current one) must have no cycle and no chain longer than
     * {@link #MAX_DELEGATION_DEPTH}.
     */
    private void validateDelegates(String agentId, List<AgentDelegateDefinition> delegates) {
        Set<String> seen = new HashSet<>();
        for (AgentDelegateDefinition delegate : delegates) {
            if (delegate.agentId().equals(agentId)) {
                throw new InvalidAgentDefinitionException("delegates: an agent cannot delegate to itself");
            }
            if (!seen.add(delegate.agentId())) {
                throw new InvalidAgentDefinitionException(
                        "delegates: '%s' is listed more than once".formatted(delegate.agentId()));
            }
            if (latestOf(delegate.agentId()).isEmpty()) {
                throw new InvalidAgentDefinitionException(
                        "delegates: agent '%s' does not exist or has no definition".formatted(delegate.agentId()));
            }
        }
        for (AgentDelegateDefinition delegate : delegates) {
            walk(delegate.agentId(), new ArrayList<>(List.of(agentId)), agentId);
        }
    }

    private void walk(String agentId, List<String> path, String root) {
        if (path.contains(agentId)) {
            List<String> cycle = new ArrayList<>(path);
            cycle.add(agentId);
            throw new InvalidAgentDefinitionException(
                    "delegates: delegation cycle " + String.join(" > ", cycle));
        }
        path.add(agentId);
        if (path.size() - 1 > MAX_DELEGATION_DEPTH) {
            throw new InvalidAgentDefinitionException("delegates: delegation chain %s is deeper than %d"
                    .formatted(String.join(" > ", path), MAX_DELEGATION_DEPTH));
        }
        List<AgentDelegateDefinition> next = latestOf(agentId).map(AgentDefinition::delegates).orElse(List.of());
        for (AgentDelegateDefinition delegate : next == null ? List.<AgentDelegateDefinition>of() : next) {
            walk(delegate.agentId(), path, root);
        }
        path.remove(path.size() - 1);
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
