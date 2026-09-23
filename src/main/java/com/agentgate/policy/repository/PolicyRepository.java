package com.agentgate.policy.repository;

import com.agentgate.policy.domain.Policy;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyRepository extends JpaRepository<Policy, Long> {

    @Override
    @Cacheable("policies")
    List<Policy> findAll();
}
