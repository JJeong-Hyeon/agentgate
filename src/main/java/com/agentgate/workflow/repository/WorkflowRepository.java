package com.agentgate.workflow.repository;

import com.agentgate.workflow.domain.Workflow;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowRepository extends JpaRepository<Workflow, Long> {

    Optional<Workflow> findByWorkflowId(String workflowId);

    // Serializes "new version" requests so two of them cannot take the same version number.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Workflow w where w.workflowId = :workflowId")
    Optional<Workflow> findForUpdate(@Param("workflowId") String workflowId);

    boolean existsByWorkflowId(String workflowId);

    List<Workflow> findAllByOrderByUpdatedAtDesc();
}
