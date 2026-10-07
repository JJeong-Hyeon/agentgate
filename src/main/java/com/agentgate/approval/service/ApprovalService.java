package com.agentgate.approval.service;

import com.agentgate.approval.domain.ApprovalRequest;
import com.agentgate.approval.domain.ApprovalStatus;
import com.agentgate.approval.dto.ApprovalResponse;
import com.agentgate.approval.repository.ApprovalRequestRepository;
import com.agentgate.common.exception.ApprovalNotFoundException;
import com.agentgate.risk.RiskLevel;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final ApprovalRequestRepository approvalRequestRepository;

    @Transactional
    public ApprovalRequest createRequest(String agentId, String action, String target, List<String> labels, RiskLevel riskLevel,
                                         String executionId) {
        ApprovalRequest approvalRequest = new ApprovalRequest(agentId, action, target, labels, riskLevel, executionId);
        return approvalRequestRepository.save(approvalRequest);
    }

    @Transactional
    public ApprovalResponse approve(Long id, String decidedBy) {
        ApprovalRequest approvalRequest = findOrThrow(id);
        approvalRequest.approve(decidedBy);
        return ApprovalResponse.from(approvalRequest);
    }

    @Transactional
    public ApprovalResponse reject(Long id, String decidedBy) {
        ApprovalRequest approvalRequest = findOrThrow(id);
        approvalRequest.reject(decidedBy);
        return ApprovalResponse.from(approvalRequest);
    }

    @Transactional(readOnly = true)
    public ApprovalResponse get(Long id) {
        return ApprovalResponse.from(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public List<ApprovalResponse> list(ApprovalStatus statusFilter, String executionId) {
        List<ApprovalRequest> approvalRequests;
        if (executionId != null) {
            approvalRequests = (statusFilter == null)
                    ? approvalRequestRepository.findByExecutionId(executionId)
                    : approvalRequestRepository.findByStatusAndExecutionId(statusFilter, executionId);
        } else {
            approvalRequests = (statusFilter == null)
                    ? approvalRequestRepository.findAll()
                    : approvalRequestRepository.findByStatus(statusFilter);
        }
        return approvalRequests.stream().map(ApprovalResponse::from).toList();
    }

    private ApprovalRequest findOrThrow(Long id) {
        return approvalRequestRepository.findById(id).orElseThrow(() -> new ApprovalNotFoundException(id));
    }
}
