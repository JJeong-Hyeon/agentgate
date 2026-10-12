package com.agentgate.audit.repository;

import com.agentgate.audit.domain.AuditLog;
import com.agentgate.risk.ActionStatus;
import com.agentgate.risk.RiskLevel;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** Newest first; a null filter matches everything. */
    @Query("""
            select a from AuditLog a
            where (:agentId is null or a.agentId = :agentId)
              and (:executionId is null or a.executionId = :executionId)
              and (:status is null or a.status = :status)
              and (:riskLevel is null or a.riskLevel = :riskLevel)
            order by a.createdAt desc, a.id desc""")
    List<AuditLog> search(@Param("agentId") String agentId, @Param("executionId") String executionId,
                          @Param("status") ActionStatus status, @Param("riskLevel") RiskLevel riskLevel,
                          Pageable page);

    @Query("""
            select a.riskLevel, count(a) from AuditLog a
            where (:agentId is null or a.agentId = :agentId)
            group by a.riskLevel""")
    List<Object[]> countByRiskLevel(@Param("agentId") String agentId);

    @Query("""
            select a.status, count(a) from AuditLog a
            where (:agentId is null or a.agentId = :agentId)
            group by a.status""")
    List<Object[]> countByStatus(@Param("agentId") String agentId);
}
