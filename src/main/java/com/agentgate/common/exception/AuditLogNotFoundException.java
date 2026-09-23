package com.agentgate.common.exception;

public class AuditLogNotFoundException extends RuntimeException {

    public AuditLogNotFoundException(Long id) {
        super("Audit log '%d' not found".formatted(id));
    }
}
