package com.agentgate.common.exception;

public class DuplicateUserException extends RuntimeException {

    public DuplicateUserException(String username) {
        super("User '%s' already exists".formatted(username));
    }
}
