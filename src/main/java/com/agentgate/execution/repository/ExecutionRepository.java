package com.agentgate.execution.repository;

import com.agentgate.execution.domain.Execution;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionRepository extends JpaRepository<Execution, Long> {

    Optional<Execution> findByExecutionId(String executionId);

    List<Execution> findTop50ByOrderByCreatedAtDesc();

    List<Execution> findTop50ByWorkflowIdOrderByCreatedAtDesc(String workflowId);
}
