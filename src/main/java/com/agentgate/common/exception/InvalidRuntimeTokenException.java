package com.agentgate.common.exception;

public class InvalidRuntimeTokenException extends RuntimeException {

    public InvalidRuntimeTokenException() {
        super("Missing or invalid runtime token");
    }
}
