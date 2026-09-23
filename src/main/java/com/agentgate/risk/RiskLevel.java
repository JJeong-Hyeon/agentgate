package com.agentgate.risk;

public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    BLOCKED;

    public ActionStatus toActionStatus() {
        return switch (this) {
            case LOW, MEDIUM -> ActionStatus.ALLOWED;
            case HIGH -> ActionStatus.APPROVAL_REQUIRED;
            case BLOCKED -> ActionStatus.BLOCKED;
        };
    }
}
