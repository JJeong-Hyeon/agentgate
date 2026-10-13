package com.agentgate.execution.repository;

import com.agentgate.execution.domain.Execution;
import com.agentgate.execution.domain.NodeExecution;
import com.agentgate.execution.domain.NodeStatus;
import com.agentgate.dashboard.ModelUsage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NodeExecutionRepository extends JpaRepository<NodeExecution, Long> {

    Optional<NodeExecution> findByExecutionAndTaskId(Execution execution, String taskId);

    List<NodeExecution> findByExecutionOrderByIdAsc(Execution execution);

    List<NodeExecution> findByExecutionAndStatus(Execution execution, NodeStatus status);

    @Modifying
    @Query("delete from NodeExecution n where n.execution = :execution")
    int deleteByExecution(@Param("execution") Execution execution);

    /** Model calls per model since {@code since}: calls, average latency and tokens. */
    @Query("select new com.agentgate.dashboard.ModelUsage(n.llmModel, count(n), avg(n.llmLatencyMs), "
            + "sum(n.inputTokens), sum(n.outputTokens)) "
            + "from NodeExecution n where n.finishedAt >= :since and n.llmLatencyMs is not null "
            + "group by n.llmModel order by count(n) desc")
    List<ModelUsage> modelUsageSince(@Param("since") Instant since);
}
