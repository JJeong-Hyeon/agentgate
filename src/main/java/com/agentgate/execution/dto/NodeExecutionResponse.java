package com.agentgate.execution.dto;

import com.agentgate.execution.domain.NodeExecution;
import com.agentgate.execution.domain.NodeStatus;
import java.time.Instant;

public record NodeExecutionResponse(
        String taskId,
        String nodeId,
        String step,
        NodeStatus status,
        String output,
        String error,
        Long approvalId,
        Instant startedAt,
        Instant finishedAt
) {
    public static NodeExecutionResponse from(NodeExecution node) {
        return new NodeExecutionResponse(node.getTaskId(), node.getNodeId(), node.getStep(), node.getStatus(),
                node.getOutput(), node.getError(), node.getApprovalId(), node.getStartedAt(), node.getFinishedAt());
    }
}
