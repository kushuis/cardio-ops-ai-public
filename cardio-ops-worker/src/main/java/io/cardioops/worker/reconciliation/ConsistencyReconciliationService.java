package io.cardioops.worker.reconciliation;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * 定时把几类对不齐的数量扫出来。只扫描，只打日志和指标，不改任何业务行。
 *
 * <p>多实例用事务级 advisory lock 保证同一时刻只有一个在扫。锁跟着事务走，事务结束锁就没了。
 * 没抢到锁就记一条跳过，报告里 lockAcquired 是 false，各项计数是 0。不要排队再扫一遍。</p>
 *
 * <p>扫的是：应传未传案件没有 Run、已完成的 Run 没有结果、RUNNING 租约过期、
 * 关闭建议停在 PENDING 太久、死信还是 PENDING。这些数字是给人对一下的。
 * 过期的批准只计数，不替主管拒绝或通过。死信也只计数，重放不在这次扫描里发生。</p>
 *
 * <p>这一层的 Span 叫 reconciliation.scan，一次扫描一段。
 * 要不要导出看 management.opentelemetry.enabled，也就是 CARDIO_OPS_OTEL_ENABLED，默认关着。</p>
 *
 * <p>
 * 哪一行能安全补，是业务决定。巡检一旦自己改状态，就会绕过租约和批准。这里只负责把数报出来。</p>
 */
@Component
@ConditionalOnExpression("'${cardio-ops.runtime.role:}' == 'worker' && ${cardio-ops.integration.enabled:false}")
public final class ConsistencyReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ConsistencyReconciliationService.class);
    private static final long LOCK_KEY = 2_026_110_011L;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ReconciliationProperties properties;
    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public ConsistencyReconciliationService(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            ReconciliationProperties properties,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry,
            Clock clock
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${cardio-ops.reconciliation.interval:5m}",
            initialDelayString = "${cardio-ops.reconciliation.initial-delay:1m}")
    public void scheduledScan() {
        scan();
    }

    /**
     * 扫描包在一个事务里，是为了让 advisory lock 覆盖整次计数。
     * 事务提交的时候只是结束这次只读扫描，没有业务更新。
     *
     * <p>
     * 用的是事务级锁。没有事务，锁不住“同一时刻只扫一次”，两个实例会各报一份互相打架的数字。</p>
     */
    public ReconciliationReport scan() {
        return Observation.createNotStarted("reconciliation.scan", observationRegistry)
                .lowCardinalityKeyValue("reconciliation.kind", "cardio_ops_consistency")
                .observe(() -> transactionTemplate.execute(status -> scanInTransaction()));
    }

    private ReconciliationReport scanInTransaction() {
        String runId = UUID.randomUUID().toString();
        Instant startedAt = clock.instant();
        Boolean acquired = jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_xact_lock(?)", Boolean.class, LOCK_KEY);
        if (!Boolean.TRUE.equals(acquired)) {
            log.info("consistency reconciliation skipped runId={} reason=another_instance_running", runId);
            return new ReconciliationReport(runId, false, 0, 0, 0, 0, 0,
                    0, clock.instant());
        }

        long incidentsWithoutRun = countIncidentsWithoutRun();
        long completedWithoutResult = countCompletedRunsWithoutResult();
        long expiredLeases = countExpiredRunningLeases();
        long staleApprovals = countStalePendingApprovals();
        long pendingDlt = countPendingDeadLetters();
        Instant completedAt = clock.instant();
        long durationMillis = java.time.Duration.between(startedAt, completedAt).toMillis();
        var report = new ReconciliationReport(runId, true, incidentsWithoutRun, completedWithoutResult,
                expiredLeases, staleApprovals, pendingDlt, durationMillis, completedAt);

        recordDifference("incident_without_agent_run", incidentsWithoutRun);
        recordDifference("completed_run_without_result", completedWithoutResult);
        recordDifference("expired_running_lease", expiredLeases);
        recordDifference("stale_pending_approval", staleApprovals);
        recordDifference("pending_dead_letter", pendingDlt);
        Timer.builder("cardio_ops.reconciliation.duration")
                .publishPercentileHistogram().register(meterRegistry)
                .record(java.time.Duration.ofMillis(durationMillis));
        log.info("consistency reconciliation completed runId={} incidentWithoutRun={} "
                        + "completedWithoutResult={} expiredLeases={} staleApprovals={} pendingDlt={} "
                        + "totalDifferences={} durationMs={}",
                runId, incidentsWithoutRun, completedWithoutResult, expiredLeases, staleApprovals,
                pendingDlt, report.totalDifferences(), durationMillis);
        return report;
    }

    private long countIncidentsWithoutRun() {
        return count("""
                SELECT COUNT(*) FROM incident i
                 WHERE (i.incident_type = 'SCHEDULED_GATEWAY_TRANSMISSION_MISSED'
                        OR (i.incident_type = 'DEVICE_LOW_BATTERY' AND i.device_family = 'ICM'))
                   AND i.status <> 'CLOSED'
                   AND NOT EXISTS (SELECT 1 FROM agent_run r WHERE r.incident_id = i.incident_id)
                """);
    }

    private long countCompletedRunsWithoutResult() {
        return count("""
                SELECT COUNT(*) FROM agent_run r
                 WHERE r.run_status = 'COMPLETED'
                   AND NOT EXISTS (SELECT 1 FROM investigation_result result WHERE result.run_id = r.run_id)
                   AND NOT EXISTS (SELECT 1 FROM low_battery_investigation_result result WHERE result.run_id = r.run_id)
                """);
    }

    private long countExpiredRunningLeases() {
        return count("""
                SELECT COUNT(*) FROM agent_run
                 WHERE run_status = 'RUNNING'
                   AND (lease_expires_at IS NULL OR lease_expires_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 second'))
                """, properties.runningTimeout().toSeconds());
    }

    private long countStalePendingApprovals() {
        return count("""
                SELECT COUNT(*) FROM customer_service_closure_proposal
                 WHERE proposal_status = 'PENDING'
                   AND created_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 second')
                """, properties.approvalTimeout().toSeconds());
    }

    private long countPendingDeadLetters() {
        return count("SELECT COUNT(*) FROM dead_letter_event WHERE replay_status = 'PENDING'");
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }

    private void recordDifference(String type, long count) {
        DistributionSummary.builder("cardio_ops.reconciliation.differences")
                .tag("type", type)
                .register(meterRegistry)
                .record(count);
    }
}
