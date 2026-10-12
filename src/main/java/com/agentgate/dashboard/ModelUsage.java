package com.agentgate.dashboard;

/** One model's calls in a period; token sums are null when the model server reported none. */
public record ModelUsage(String model, Long calls, Double avgLatencyMs, Long inputTokens, Long outputTokens) {
}
