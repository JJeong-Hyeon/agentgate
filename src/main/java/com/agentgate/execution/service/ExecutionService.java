package com.agentgate.execution.service;

import com.agentgate.common.exception.ExecutionNotFoundException;
import com.agentgate.execution.domain.Execution;
import com.agentgate.execution.domain.NodeExecution;
import com.agentgate.execution.domain.NodeStatus;
import com.agentgate.execution.dto.ExecutionEvent;
import com.agentgate.execution.dto.ExecutionResponse;
import com.agentgate.execution.dto.ExecutionStartRequest;
import com.agentgate.execution.dto.NodeExecutionResponse;
import com.agentgate.execution.repository.ExecutionRepository;
import com.agentgate.execution.repository.NodeExecutionRepository;
import com.agentgate.runtime.RuntimeClient;
import com.agentgate.workflow.dto.WorkflowVersionResponse;
import com.agentgate.workflow.service.WorkflowService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ExecutionService {

    private final ExecutionRepository executionRepository;
    private final NodeExecutionRepository nodeExecutionRepository;
    private final WorkflowService workflowService;
    private final RuntimeClient runtimeClient;
    private final TransactionTemplate transactionTemplate;

    public ExecutionService(ExecutionRepository executionRepository, NodeExecutionRepository nodeExecutionRepository,
                            WorkflowService workflowService, RuntimeClient runtimeClient,
                            PlatformTransactionManager transactionManager) {
        this.executionRepository = executionRepository;
        this.nodeExecutionRepository = nodeExecutionRepository;
        this.workflowService = workflowService;
        this.runtimeClient = runtimeClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Records the execution, then asks the runtime to run it. The record is committed first so the runtime's
     * progress events always find it.
     */
    public ExecutionResponse start(ExecutionStartRequest request) {
        WorkflowVersionResponse workflow = workflowService.resolve(request.workflowId(), request.version());
        String executionId = UUID.randomUUID().toString();
        transactionTemplate.executeWithoutResult(status -> executionRepository.save(
                new Execution(executionId, workflow.workflowId(), workflow.version(), request.task())));
        try {
            runtimeClient.startExecution(executionId, request.task(), workflow.dsl());
        } catch (RuntimeException e) {
            transactionTemplate.executeWithoutResult(status ->
                    findOrThrow(executionId).failed(e.getMessage(), Instant.now()));
            throw e;
        }
        return get(executionId);
    }

    @Transactional(readOnly = true)
    public ExecutionResponse get(String executionId) {
        Execution execution = findOrThrow(executionId);
        List<NodeExecutionResponse> nodes = nodeExecutionRepository.findByExecutionOrderByIdAsc(execution).stream()
                .map(NodeExecutionResponse::from)
                .toList();
        return ExecutionResponse.from(execution, nodes);
    }

    @Transactional(readOnly = true)
    public List<ExecutionResponse> list(String workflowId) {
        List<Execution> executions = (workflowId == null)
                ? executionRepository.findTop50ByOrderByCreatedAtDesc()
                : executionRepository.findTop50ByWorkflowIdOrderByCreatedAtDesc(workflowId);
        return executions.stream().map(e -> ExecutionResponse.from(e, null)).toList();
    }

    /** Applies a runtime progress event and returns the execution's resulting state. */
    @Transactional
    public ExecutionResponse apply(String executionId, ExecutionEvent event) {
        Execution execution = findOrThrow(executionId);
        Instant at = event.atOrNow();
        NodeExecution node = event.isNodeEvent() ? nodeFor(execution, event) : null;
        switch (event.type()) {
            case NODE_STARTED -> {
                node.started(at);
                execution.running();
            }
            case NODE_COMPLETED -> node.completed(event.output(), at);
            case NODE_WAITING -> node.waiting(event.approvalId());
            case NODE_FAILED -> node.failed(event.error(), at);
            case EXECUTION_WAITING -> execution.waitingForApproval(event.approvalId());
            case EXECUTION_COMPLETED -> execution.completed(at);
            case EXECUTION_FAILED -> {
                execution.failed(event.error(), at);
                // The runtime stops before reporting the failed step itself.
                nodeExecutionRepository.findByExecutionAndStatus(execution, NodeStatus.RUNNING)
                        .forEach(running -> running.failed(event.error(), at));
            }
        }
        return ExecutionResponse.from(execution, node == null ? null : List.of(NodeExecutionResponse.from(node)));
    }

    private NodeExecution nodeFor(Execution execution, ExecutionEvent event) {
        return nodeExecutionRepository.findByExecutionAndTaskId(execution, event.taskId())
                .orElseGet(() -> nodeExecutionRepository.save(
                        new NodeExecution(execution, event.taskId(), event.nodeId(), event.step())));
    }

    private Execution findOrThrow(String executionId) {
        return executionRepository.findByExecutionId(executionId)
                .orElseThrow(() -> new ExecutionNotFoundException(executionId));
    }
}
