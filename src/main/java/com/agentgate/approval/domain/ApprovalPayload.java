package com.agentgate.approval.domain;

/**
 * What the runtime said it will run for an approval: the SHA-256 of the real arguments (checked before running) and
 * a readable preview of them with secrets masked (shown to the approver).
 */
public record ApprovalPayload(String hash, String preview) {
}
