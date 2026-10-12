package com.agentgate.dashboard;

import com.agentgate.approval.domain.ApprovalRequest;
import com.agentgate.approval.domain.ApprovalStatus;
import com.agentgate.approval.repository.ApprovalRequestRepository;
import com.agentgate.audit.repository.AuditLogRepository;
import com.agentgate.common.exception.InvalidDashboardPeriodException;
import com.agentgate.dashboard.DashboardResponse.ActionCount;
import com.agentgate.dashboard.DashboardResponse.Approvals;
import com.agentgate.dashboard.DashboardResponse.Bucket;
import com.agentgate.dashboard.DashboardResponse.Executions;
import com.agentgate.dashboard.DashboardResponse.Governance;
import com.agentgate.dashboard.DashboardResponse.PendingApproval;
import com.agentgate.dashboard.DashboardResponse.Problem;
import com.agentgate.execution.domain.ExecutionStatus;
import com.agentgate.execution.repository.ExecutionRepository;
import com.agentgate.execution.repository.NodeExecutionRepository;
import com.agentgate.risk.ActionStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Aggregates what the console's dashboard shows; every figure comes from the database, so restarts keep it. */
@Service
public class DashboardService {

    /** The periods the dashboard offers, with their bucket size. */
    public enum Period {
        DAY("24h", Duration.ofHours(24), ChronoUnit.HOURS),
        WEEK("7d", Duration.ofDays(7), ChronoUnit.DAYS);

        private final String label;
        private final Duration length;
        private final ChronoUnit bucket;

        Period(String label, Duration length, ChronoUnit bucket) {
            this.label = label;
            this.length = length;
            this.bucket = bucket;
        }

        public static Period of(String label) {
            for (Period period : values()) {
                if (period.label.equals(label)) {
                    return period;
                }
            }
            throw new InvalidDashboardPeriodException(label);
        }
    }

    private final ExecutionRepository executionRepository;
    private final NodeExecutionRepository nodeExecutionRepository;
    private final ApprovalRequestRepository approvalRepository;
    private final AuditLogRepository auditLogRepository;
    private final Clock clock;

    @Autowired
    public DashboardService(ExecutionRepository executionRepository, NodeExecutionRepository nodeExecutionRepository,
                            ApprovalRequestRepository approvalRepository, AuditLogRepository auditLogRepository) {
        this(executionRepository, nodeExecutionRepository, approvalRepository, auditLogRepository, Clock.systemUTC());
    }

    DashboardService(ExecutionRepository executionRepository, NodeExecutionRepository nodeExecutionRepository,
                     ApprovalRequestRepository approvalRepository, AuditLogRepository auditLogRepository, Clock clock) {
        this.executionRepository = executionRepository;
        this.nodeExecutionRepository = nodeExecutionRepository;
        this.approvalRepository = approvalRepository;
        this.auditLogRepository = auditLogRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DashboardResponse get(Period period) {
        Instant now = clock.instant();
        // Buckets are whole hours/days so the chart's bars line up with the clock.
        Instant firstBucket = now.minus(period.length).truncatedTo(period.bucket).plus(1, period.bucket);
        Instant since = firstBucket;
        return new DashboardResponse(period.label, since, executions(since, firstBucket, now, period),
                approvals(since), governance(since), nodeExecutionRepository.modelUsageSince(since), problems(since));
    }

    private Executions executions(Instant since, Instant firstBucket, Instant now, Period period) {
        List<ExecutionTiming> timings = executionRepository.timingsSince(since);
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (ExecutionStatus status : ExecutionStatus.values()) {
            byStatus.put(status.name(), 0L);
        }
        List<Bucket> buckets = new ArrayList<>();
        List<long[]> counts = new ArrayList<>();
        for (Instant start = firstBucket; !start.isAfter(now); start = start.plus(1, period.bucket)) {
            counts.add(new long[4]);
        }
        OptionalDouble avgDuration = timings.stream()
                .filter(t -> t.finishedAt() != null)
                .mapToLong(t -> Duration.between(t.createdAt(), t.finishedAt()).toMillis())
                .average();
        for (ExecutionTiming timing : timings) {
            byStatus.merge(timing.status().name(), 1L, Long::sum);
            int index = (int) period.bucket.between(firstBucket, timing.createdAt().truncatedTo(period.bucket));
            if (index >= 0 && index < counts.size()) {
                long[] count = counts.get(index);
                count[0]++;
                switch (timing.status()) {
                    case COMPLETED -> count[1]++;
                    case STOPPED -> count[2]++;
                    case FAILED -> count[3]++;
                    default -> { }
                }
            }
        }
        for (int i = 0; i < counts.size(); i++) {
            long[] count = counts.get(i);
            buckets.add(new Bucket(firstBucket.plus(i, period.bucket), count[0], count[1], count[2], count[3]));
        }
        return new Executions(timings.size(), byStatus,
                executionRepository.countByStatus(ExecutionStatus.RUNNING),
                executionRepository.countByStatus(ExecutionStatus.WAITING_APPROVAL),
                avgDuration.isPresent() ? Math.round(avgDuration.getAsDouble()) : null,
                buckets);
    }

    private Approvals approvals(Instant since) {
        List<PendingApproval> oldest = approvalRepository.findTop5ByStatusOrderByCreatedAtAsc(ApprovalStatus.PENDING)
                .stream()
                .map(a -> new PendingApproval(a.getId(), a.getAgentId(), a.getAction(), a.getRiskLevel(),
                        a.getExecutionId(), a.getCreatedAt()))
                .toList();
        Map<String, Long> decided = new LinkedHashMap<>();
        for (ApprovalStatus status : List.of(ApprovalStatus.APPROVED, ApprovalStatus.REJECTED, ApprovalStatus.EXPIRED)) {
            decided.put(status.name(), 0L);
        }
        List<ApprovalRequest> decidedInPeriod = approvalRepository.findByDecidedAtGreaterThanEqual(since);
        decidedInPeriod.forEach(a -> decided.merge(a.getStatus().name(), 1L, Long::sum));
        OptionalDouble avgWait = decidedInPeriod.stream()
                .filter(a -> a.getCreatedAt() != null)
                .mapToLong(a -> Duration.between(a.getCreatedAt(), a.getDecidedAt()).toMillis())
                .average();
        return new Approvals(approvalRepository.countByStatus(ApprovalStatus.PENDING), oldest, decided,
                avgWait.isPresent() ? Math.round(avgWait.getAsDouble()) : null);
    }

    private Governance governance(Instant since) {
        Map<ActionStatus, Long> byStatus = new EnumMap<>(ActionStatus.class);
        auditLogRepository.countByStatusSince(since).forEach(row -> byStatus.put((ActionStatus) row[0], (Long) row[1]));
        long allowed = byStatus.getOrDefault(ActionStatus.ALLOWED, 0L);
        long approvalRequired = byStatus.getOrDefault(ActionStatus.APPROVAL_REQUIRED, 0L);
        long blocked = byStatus.getOrDefault(ActionStatus.BLOCKED, 0L);
        long total = allowed + approvalRequired + blocked;
        List<ActionCount> topBlocked = auditLogRepository.topBlockedActionsSince(since, PageRequest.of(0, 5)).stream()
                .map(row -> new ActionCount((String) row[0], (Long) row[1]))
                .toList();
        return new Governance(total, allowed, approvalRequired, blocked, total == 0 ? 0.0 : blocked / (double) total,
                topBlocked);
    }

    private List<Problem> problems(Instant since) {
        return executionRepository.findTop5ByStatusInAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                        List.of(ExecutionStatus.FAILED, ExecutionStatus.STOPPED), since).stream()
                .map(e -> new Problem(e.getExecutionId(), e.getWorkflowId(), e.getStatus().name(), e.getError(),
                        e.getCreatedAt()))
                .toList();
    }
}
