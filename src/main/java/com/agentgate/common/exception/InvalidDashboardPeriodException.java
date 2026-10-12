package com.agentgate.common.exception;

public class InvalidDashboardPeriodException extends RuntimeException {

    public InvalidDashboardPeriodException(String period) {
        super("Unknown period '%s': use 24h or 7d".formatted(period));
    }
}
