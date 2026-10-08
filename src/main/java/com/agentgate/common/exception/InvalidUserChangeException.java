package com.agentgate.common.exception;

/** A user change that is well-formed but not allowed, e.g. removing the last administrator. */
public class InvalidUserChangeException extends RuntimeException {

    public InvalidUserChangeException(String message) {
        super(message);
    }
}
