package com.agentgate.common.exception;

public class IllegalApprovalStateException extends RuntimeException {

    public IllegalApprovalStateException(Long id, Object currentStatus) {
        super("Approval request '%d' is already decided (status: %s)".formatted(id, currentStatus));
    }
}
