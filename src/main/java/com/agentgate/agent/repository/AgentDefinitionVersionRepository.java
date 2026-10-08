package com.agentgate.agent.repository;

import com.agentgate.agent.domain.Agent;
import com.agentgate.agent.domain.AgentDefinitionVersion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentDefinitionVersionRepository extends JpaRepository<AgentDefinitionVersion, Long> {

    Optional<AgentDefinitionVersion> findByAgentAndVersion(Agent agent, int version);

    List<AgentDefinitionVersion> findByAgentOrderByVersionDesc(Agent agent);
}
