package com.agentgate.workflow.repository;

import com.agentgate.workflow.domain.Workflow;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRepository extends JpaRepository<Workflow, Long> {

    Optional<Workflow> findByWorkflowId(String workflowId);

    boolean existsByWorkflowId(String workflowId);

    List<Workflow> findAllByOrderByUpdatedAtDesc();
}
