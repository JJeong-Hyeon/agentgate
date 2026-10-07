package com.agentgate.workflow.service;

import com.agentgate.common.exception.DuplicateWorkflowException;
import com.agentgate.common.exception.InvalidWorkflowException;
import com.agentgate.common.exception.WorkflowNotFoundException;
import com.agentgate.runtime.RuntimeClient;
import com.agentgate.runtime.WorkflowValidation;
import com.agentgate.workflow.domain.Workflow;
import com.agentgate.workflow.domain.WorkflowVersion;
import com.agentgate.workflow.dto.WorkflowCreateRequest;
import com.agentgate.workflow.dto.WorkflowResponse;
import com.agentgate.workflow.dto.WorkflowVersionResponse;
import com.agentgate.workflow.repository.WorkflowRepository;
import com.agentgate.workflow.repository.WorkflowVersionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@RequiredArgsConstructor
public class WorkflowService {

    private final WorkflowRepository workflowRepository;
    private final WorkflowVersionRepository workflowVersionRepository;
    private final RuntimeClient runtimeClient;
    private final ObjectMapper objectMapper;

    @Transactional
    public WorkflowResponse create(WorkflowCreateRequest request) {
        if (workflowRepository.existsByWorkflowId(request.workflowId())) {
            throw new DuplicateWorkflowException(request.workflowId());
        }
        Workflow workflow = new Workflow(request.workflowId(), nameOf(request.name(), request.dsl(), request.workflowId()));
        JsonNode dsl = saveVersion(workflowRepository.save(workflow), request.dsl());
        return WorkflowResponse.from(workflow, dsl);
    }

    @Transactional
    public WorkflowVersionResponse addVersion(String workflowId, JsonNode dsl) {
        Workflow workflow = workflowRepository.findForUpdate(workflowId)
                .orElseThrow(() -> new WorkflowNotFoundException("Workflow '%s' not found".formatted(workflowId)));
        JsonNode saved = saveVersion(workflow, dsl);
        if (dsl.hasNonNull("name")) {
            workflow.rename(dsl.get("name").asString());
        }
        return new WorkflowVersionResponse(workflowId, workflow.getLatestVersion(), null, saved);
    }

    @Transactional(readOnly = true)
    public List<WorkflowResponse> list() {
        return workflowRepository.findAllByOrderByUpdatedAtDesc().stream()
                .map(workflow -> WorkflowResponse.from(workflow, null))
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkflowResponse get(String workflowId) {
        Workflow workflow = findOrThrow(workflowId);
        return WorkflowResponse.from(workflow, parse(findVersion(workflow, workflow.getLatestVersion()).getDsl()));
    }

    @Transactional(readOnly = true)
    public List<WorkflowVersionResponse> versions(String workflowId) {
        return workflowVersionRepository.findByWorkflowOrderByVersionDesc(findOrThrow(workflowId)).stream()
                .map(v -> new WorkflowVersionResponse(workflowId, v.getVersion(), v.getCreatedAt(), null))
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkflowVersionResponse version(String workflowId, int version) {
        WorkflowVersion found = findVersion(findOrThrow(workflowId), version);
        return new WorkflowVersionResponse(workflowId, version, found.getCreatedAt(), parse(found.getDsl()));
    }

    /** Stamps the DSL with the workflow id and the next version number, validates it, and stores it. */
    private JsonNode saveVersion(Workflow workflow, JsonNode dsl) {
        if (!dsl.isObject()) {
            throw new InvalidWorkflowException("dsl must be a JSON object", List.of());
        }
        int version = workflow.nextVersion();
        ObjectNode stamped = ((ObjectNode) dsl).deepCopy();
        stamped.put("workflowId", workflow.getWorkflowId());
        stamped.put("version", version);

        WorkflowValidation validation = runtimeClient.validateWorkflow(stamped);
        if (!validation.valid()) {
            throw new InvalidWorkflowException("Workflow DSL is invalid", validation.errors());
        }
        workflowVersionRepository.save(new WorkflowVersion(workflow, version, objectMapper.writeValueAsString(stamped)));
        return stamped;
    }

    private Workflow findOrThrow(String workflowId) {
        return workflowRepository.findByWorkflowId(workflowId)
                .orElseThrow(() -> new WorkflowNotFoundException("Workflow '%s' not found".formatted(workflowId)));
    }

    private WorkflowVersion findVersion(Workflow workflow, int version) {
        return workflowVersionRepository.findByWorkflowAndVersion(workflow, version)
                .orElseThrow(() -> new WorkflowNotFoundException(
                        "Workflow '%s' has no version %d".formatted(workflow.getWorkflowId(), version)));
    }

    private JsonNode parse(String dsl) {
        return objectMapper.readTree(dsl);
    }

    private static String nameOf(String requested, JsonNode dsl, String workflowId) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        return dsl.hasNonNull("name") ? dsl.get("name").asString() : workflowId;
    }
}
