package io.cardioops.domain.agent;

import java.time.Instant;
import java.util.Objects;

/**
 * 一次调查执行的审计根。技术状态在这里：有没有领到、租约是谁的、跑到第几次。
 * 客服有没有处理患者，不写在这条记录上。
 *
 * RUNNING 必须带齐 owner、token 和过期时间。别的状态不能残留租约，免得看起来还有人在跑。
 * attempt 大于 1 表示上次没正常结束，这次是把同一条执行接回来。图从哪个节点继续，由 Checkpoint 续跑崩溃的线程，不是等人批准。
 *
 *
 * 留着租约会让下一次领取分不清这单是不是还被占着。租约只属于正在跑的那一次。
 */
public record AgentRun(
        String runId,
        String incidentId,
        String triggerEventId,
        String graphThreadId,
        AgentRunStatus status,
        String profileId,
        String traceId,
        int executionAttempt,
        String leaseOwner,
        String leaseToken,
        Instant leaseExpiresAt,
        Instant createdAt,
        Instant updatedAt
) {
    public AgentRun {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(incidentId, "incidentId must not be null");
        Objects.requireNonNull(triggerEventId, "triggerEventId must not be null");
        Objects.requireNonNull(graphThreadId, "graphThreadId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
        if (executionAttempt < 0) {
            throw new IllegalArgumentException("executionAttempt must not be negative");
        }
        if (status == AgentRunStatus.RUNNING) {
            if (leaseOwner == null || leaseOwner.isBlank()
                    || leaseToken == null || leaseToken.isBlank()
                    || leaseExpiresAt == null) {
                throw new IllegalArgumentException("running AgentRun must have a complete lease");
            }
        } else if (leaseOwner != null || leaseToken != null || leaseExpiresAt != null) {
            throw new IllegalArgumentException("only running AgentRun may keep a lease");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    /** 第 2 次及以后领取都表示上一次 Worker 没有正常结束，需要从持久化 Checkpoint 恢复。 */
    public boolean recoveredExecution() {
        return executionAttempt > 1;
    }
}
