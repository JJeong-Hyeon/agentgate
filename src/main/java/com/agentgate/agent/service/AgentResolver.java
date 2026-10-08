package com.agentgate.agent.service;

import com.agentgate.agent.domain.Agent;
import com.agentgate.agent.dto.AgentDefinition;
import com.agentgate.agent.repository.AgentRepository;
import com.agentgate.common.exception.AgentDefinitionNotFoundException;
import com.agentgate.common.exception.InvalidWorkflowException;
import com.agentgate.runtime.WorkflowValidation.Issue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Agents a workflow's AGENT nodes refer to ({@code config.agentId}, optionally pinned by
 * {@code config.agentVersion}): checked when a workflow is saved, and resolved to definition
 * snapshots when it runs.
 */
@Service
@RequiredArgsConstructor
public class AgentResolver {

    private final AgentRepository agentRepository;
    private final AgentDefinitionService agentDefinitionService;
    private final ObjectMapper objectMapper;

    /** The agents a run uses: snapshots for the runtime and the versions they are at. */
    public record ResolvedAgents(ObjectNode snapshots, Map<String, Integer> versions) {
    }

    record Reference(String nodeId, String agentId, Integer version) {
    }

    /** Problems with the workflow's agent references, by node; empty when it may be saved. */
    @Transactional(readOnly = true)
    public List<Issue> check(JsonNode dsl) {
        List<Issue> issues = new ArrayList<>();
        pick(references(dsl), issues);
        return issues;
    }

    /** @throws InvalidWorkflowException when a referenced agent or version cannot be used */
    @Transactional(readOnly = true)
    public ResolvedAgents resolve(JsonNode dsl) {
        List<Issue> issues = new ArrayList<>();
        Map<String, AgentDefinitionWithVersion> picked = pick(references(dsl), issues);
        if (!issues.isEmpty()) {
            throw new InvalidWorkflowException("Workflow refers to agents that cannot run", issues);
        }
        ObjectNode snapshots = objectMapper.createObjectNode();
        Map<String, Integer> versions = new LinkedHashMap<>();
        picked.forEach((agentId, found) -> {
            ObjectNode snapshot = objectMapper.valueToTree(found.definition());
            snapshot.put("agentId", agentId);
            snapshot.put("version", found.version());
            snapshots.set(agentId, snapshot);
            versions.put(agentId, found.version());
        });
        return new ResolvedAgents(snapshots, versions);
    }

    private record AgentDefinitionWithVersion(int version, AgentDefinition definition) {
    }

    /** One definition version per agent: the pinned one if any node pins it, else the latest. */
    private Map<String, AgentDefinitionWithVersion> pick(List<Reference> references, List<Issue> issues) {
        Map<String, List<Reference>> byAgent = new LinkedHashMap<>();
        references.forEach(r -> byAgent.computeIfAbsent(r.agentId(), k -> new ArrayList<>()).add(r));

        Map<String, AgentDefinitionWithVersion> picked = new LinkedHashMap<>();
        byAgent.forEach((agentId, refs) -> {
            Reference first = refs.get(0);
            Optional<Agent> agent = agentRepository.findByAgentId(agentId);
            if (agent.isEmpty()) {
                refs.forEach(r -> issues.add(issue(r, "Unknown agent '%s'".formatted(agentId))));
                return;
            }
            Set<Integer> pins = new LinkedHashSet<>();
            refs.stream().map(Reference::version).filter(v -> v != null).forEach(pins::add);
            if (pins.size() > 1) {
                issues.add(issue(first, "Agent '%s' is pinned to different versions %s".formatted(agentId, pins)));
                return;
            }
            int version = pins.isEmpty() ? agent.get().getLatestDefinitionVersion() : pins.iterator().next();
            if (version == 0) {
                refs.forEach(r -> issues.add(issue(r, "Agent '%s' has no definition yet".formatted(agentId))));
                return;
            }
            try {
                picked.put(agentId, new AgentDefinitionWithVersion(version,
                        agentDefinitionService.definition(agent.get(), version)));
            } catch (AgentDefinitionNotFoundException e) {
                refs.stream().filter(r -> r.version() != null)
                        .forEach(r -> issues.add(issue(r, "Agent '%s' has no version %d".formatted(agentId, version))));
            }
        });
        return picked;
    }

    private static List<Reference> references(JsonNode dsl) {
        List<Reference> references = new ArrayList<>();
        for (JsonNode node : dsl.path("nodes")) {
            JsonNode config = node.path("config");
            if ("AGENT".equals(node.path("type").asString(null)) && config.hasNonNull("agentId")) {
                Integer version = config.hasNonNull("agentVersion") ? config.get("agentVersion").asInt() : null;
                references.add(new Reference(node.path("id").asString(null),
                        config.get("agentId").asString(), version));
            }
        }
        return references;
    }

    private static Issue issue(Reference reference, String message) {
        return new Issue("config.agentId", message, reference.nodeId());
    }
}
