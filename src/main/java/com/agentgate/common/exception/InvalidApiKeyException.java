package com.agentgate.common.exception;

public class InvalidApiKeyException extends RuntimeException {

    public InvalidApiKeyException() {
        super("Missing or invalid API key");
    }
}
