package com.agentgate.execution.repository;

import com.agentgate.execution.domain.Execution;
import com.agentgate.execution.domain.NodeExecution;
import com.agentgate.execution.domain.NodeStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NodeExecutionRepository extends JpaRepository<NodeExecution, Long> {

    Optional<NodeExecution> findByExecutionAndTaskId(Execution execution, String taskId);

    List<NodeExecution> findByExecutionOrderByIdAsc(Execution execution);

    List<NodeExecution> findByExecutionAndStatus(Execution execution, NodeStatus status);
}
