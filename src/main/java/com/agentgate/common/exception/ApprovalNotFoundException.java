package com.agentgate.common.exception;

public class ApprovalNotFoundException extends RuntimeException {

    public ApprovalNotFoundException(Long id) {
        super("Approval request '%d' not found".formatted(id));
    }
}
