package com.agentgate.dashboard;

import com.agentgate.risk.RiskLevel;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The console's operations overview for one period. */
public record DashboardResponse(
        String period,
        Instant since,
        Executions executions,
        Approvals approvals,
        Governance governance,
        List<ModelUsage> models,
        List<Problem> recentProblems
) {
    /**
     * @param byStatus     executions started in the period, by status
     * @param running      running right now (any start time)
     * @param waitingApproval paused for an approval right now
     * @param avgDurationMs average run time of those that finished in the period; null if none did
     * @param buckets      executions started per hour (24h) or day (7d), oldest first
     */
    public record Executions(long total, Map<String, Long> byStatus, long running, long waitingApproval,
                             Long avgDurationMs, List<Bucket> buckets) {
    }

    public record Bucket(Instant start, long total, long completed, long stopped, long failed) {
    }

    /**
     * @param pending       waiting for a decision right now
     * @param oldestPending the five that have waited longest
     * @param decided       decided in the period, by outcome (APPROVED / REJECTED / EXPIRED)
     * @param avgWaitMs     average time to decision in the period; null if none was decided
     */
    public record Approvals(long pending, List<PendingApproval> oldestPending, Map<String, Long> decided,
                            Long avgWaitMs) {
    }

    public record PendingApproval(Long id, String agentId, String action, RiskLevel riskLevel, String executionId,
                                  Instant createdAt) {
    }

    /** Decisions in the period; blockRate is blocked / total (0 when there were none). */
    public record Governance(long total, long allowed, long approvalRequired, long blocked, double blockRate,
                             List<ActionCount> topBlocked) {
    }

    public record ActionCount(String action, long count) {
    }

    /** An execution that failed or was stopped in the period. */
    public record Problem(String executionId, String workflowId, String status, String error, Instant createdAt) {
    }
}
