package com.agentgate.policy.service;

import com.agentgate.common.exception.PolicyNotFoundException;
import com.agentgate.policy.domain.Policy;
import com.agentgate.policy.dto.PolicyRequest;
import com.agentgate.policy.dto.PolicyResponse;
import com.agentgate.policy.repository.PolicyRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PolicyManagementService {

    private final PolicyRepository policyRepository;

    @Transactional
    @CacheEvict(cacheNames = "policies", allEntries = true)
    public PolicyResponse create(PolicyRequest request) {
        Policy policy = new Policy(request.actionType(), request.label(), request.riskLevel());
        return PolicyResponse.from(policyRepository.save(policy));
    }

    public List<PolicyResponse> list() {
        return policyRepository.findAll().stream()
                .map(PolicyResponse::from)
                .toList();
    }

    public PolicyResponse get(Long id) {
        return PolicyResponse.from(findOrThrow(id));
    }

    @Transactional
    @CacheEvict(cacheNames = "policies", allEntries = true)
    public PolicyResponse update(Long id, PolicyRequest request) {
        Policy policy = findOrThrow(id);
        policy.update(request.actionType(), request.label(), request.riskLevel());
        return PolicyResponse.from(policy);
    }

    @Transactional
    @CacheEvict(cacheNames = "policies", allEntries = true)
    public void delete(Long id) {
        if (!policyRepository.existsById(id)) {
            throw new PolicyNotFoundException(id);
        }
        policyRepository.deleteById(id);
    }

    private Policy findOrThrow(Long id) {
        return policyRepository.findById(id).orElseThrow(() -> new PolicyNotFoundException(id));
    }
}
