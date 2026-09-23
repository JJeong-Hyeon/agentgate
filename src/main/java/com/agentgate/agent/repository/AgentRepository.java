package com.agentgate.agent.repository;

import com.agentgate.agent.domain.Agent;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRepository extends JpaRepository<Agent, Long> {

    Optional<Agent> findByAgentId(String agentId);
}
