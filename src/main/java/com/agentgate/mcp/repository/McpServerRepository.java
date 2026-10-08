package com.agentgate.mcp.repository;

import com.agentgate.mcp.domain.McpServer;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpServerRepository extends JpaRepository<McpServer, Long> {

    boolean existsByName(String name);

    List<McpServer> findAllByOrderByNameAsc();

    List<McpServer> findByEnabledTrueOrderByNameAsc();
}
