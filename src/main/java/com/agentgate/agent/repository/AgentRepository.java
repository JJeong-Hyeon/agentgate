package com.agentgate.agent.repository;

import com.agentgate.agent.domain.Agent;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentRepository extends JpaRepository<Agent, Long> {

    Optional<Agent> findByAgentId(String agentId);

    // Serializes "new definition" requests so two of them cannot take the same version number.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Agent a where a.id = :id")
    Optional<Agent> findForUpdate(@Param("id") Long id);
}
