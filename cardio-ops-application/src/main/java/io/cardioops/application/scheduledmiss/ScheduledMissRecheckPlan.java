package io.cardioops.application.scheduledmiss;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 一件应传未传案件的复查计划。recheckCount 只计算已经排入队列的业务复查，
 * 同一条 AgentRun 因进程崩溃发生的租约重领不重复计数。
 */
public record ScheduledMissRecheckPlan(
        String incidentId,
        int recheckCount,
        Duration interval,
        Instant nextRecheckAt,
        String profileId
) {
    public ScheduledMissRecheckPlan {
        Objects.requireNonNull(incidentId, "incidentId must not be null");
        Objects.requireNonNull(interval, "interval must not be null");
        if (recheckCount < 0 || recheckCount > 3) {
            throw new IllegalArgumentException("recheckCount must be between 0 and 3");
        }
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("recheck interval must be positive");
        }
    }
}
