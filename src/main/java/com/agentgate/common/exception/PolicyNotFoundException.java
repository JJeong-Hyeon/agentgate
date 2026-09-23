package com.agentgate.common.exception;

public class PolicyNotFoundException extends RuntimeException {

    public PolicyNotFoundException(Long id) {
        super("Policy '%d' not found".formatted(id));
    }
}
