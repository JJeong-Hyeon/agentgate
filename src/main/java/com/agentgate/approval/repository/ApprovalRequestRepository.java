package com.agentgate.approval.repository;

import com.agentgate.approval.domain.ApprovalRequest;
import com.agentgate.approval.domain.ApprovalStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, Long> {

    List<ApprovalRequest> findByStatus(ApprovalStatus status);

    List<ApprovalRequest> findByExecutionId(String executionId);

    List<ApprovalRequest> findByStatusAndExecutionId(ApprovalStatus status, String executionId);
}
