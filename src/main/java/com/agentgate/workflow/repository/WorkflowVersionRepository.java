package com.agentgate.workflow.repository;

import com.agentgate.workflow.domain.Workflow;
import com.agentgate.workflow.domain.WorkflowVersion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowVersionRepository extends JpaRepository<WorkflowVersion, Long> {

    Optional<WorkflowVersion> findByWorkflowAndVersion(Workflow workflow, int version);

    List<WorkflowVersion> findByWorkflowOrderByVersionDesc(Workflow workflow);
}
