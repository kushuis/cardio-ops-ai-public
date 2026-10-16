package io.cardioops.worker.reconciliation;

import java.time.Instant;

/**
 * 一次巡检的只读结果。锁没拿到就不是一次真正的扫描。数字只用于告警，不触发补数据、重放或关案。
 */
public record ReconciliationReport(
        String reconciliationRunId,
        boolean lockAcquired,
        long incidentsWithoutAgentRun,
        long completedRunsWithoutResult,
        long expiredRunningLeases,
        long stalePendingApprovals,
        long pendingDeadLetters,
        long durationMillis,
        Instant completedAt
) {
    public long totalDifferences() {
        return incidentsWithoutAgentRun + completedRunsWithoutResult + expiredRunningLeases
                + stalePendingApprovals + pendingDeadLetters;
    }
}
