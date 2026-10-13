package com.agentgate.execution.repository;

import com.agentgate.execution.domain.Execution;
import com.agentgate.dashboard.ExecutionTiming;
import com.agentgate.execution.domain.ExecutionStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExecutionRepository extends JpaRepository<Execution, Long> {

    Optional<Execution> findByExecutionId(String executionId);

    List<Execution> findTop50ByOrderByCreatedAtDesc();

    List<Execution> findTop50ByWorkflowIdOrderByCreatedAtDesc(String workflowId);

    /** Start, end and outcome of every execution started since {@code since}. */
    @Query("select new com.agentgate.dashboard.ExecutionTiming(e.createdAt, e.finishedAt, e.status) "
            + "from Execution e where e.createdAt >= :since")
    List<ExecutionTiming> timingsSince(@Param("since") Instant since);

    long countByStatus(ExecutionStatus status);

    List<Execution> findByStatusAndCreatedAtBefore(ExecutionStatus status, Instant before);

    /** Executions that ended (in one of {@code statuses}) before {@code cutoff}, oldest first. */
    @Query("select e from Execution e where e.status in :statuses "
            + "and coalesce(e.finishedAt, e.updatedAt) < :cutoff order by e.id")
    List<Execution> findEndedBefore(@Param("statuses") Collection<ExecutionStatus> statuses,
                                    @Param("cutoff") Instant cutoff, Pageable page);

    List<Execution> findTop5ByStatusInAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
            Collection<ExecutionStatus> statuses, Instant since);
}
