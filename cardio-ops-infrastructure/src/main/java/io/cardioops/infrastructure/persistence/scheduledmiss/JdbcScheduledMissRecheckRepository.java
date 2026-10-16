package io.cardioops.infrastructure.persistence.scheduledmiss;

import io.cardioops.application.scheduledmiss.ScheduledMissRecheckPlan;
import io.cardioops.application.scheduledmiss.ScheduledMissRecheckStore;
import io.cardioops.infrastructure.persistence.JdbcProduct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** PostgreSQL 复查计划仓储。计划只属于应传未传案件，不污染通用 Incident 字段。 */
public final class JdbcScheduledMissRecheckRepository implements ScheduledMissRecheckStore {

    private final JdbcTemplate jdbcTemplate;
    private final boolean postgres;

    public JdbcScheduledMissRecheckRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.postgres = JdbcProduct.isPostgres(jdbcTemplate);
    }

    @Override
    public void initializeOrRefresh(
            String incidentId,
            Duration interval,
            Instant firstRecheckAt,
            boolean newlyCreated,
            Instant updatedAt
    ) {
        if (!newlyCreated && refreshSchedule(incidentId, interval, firstRecheckAt, updatedAt) == 1) {
            return;
        }

        try {
            jdbcTemplate.update("""
                    INSERT INTO scheduled_miss_recheck(
                        incident_id, recheck_count, interval_seconds,
                        next_recheck_at, created_at, updated_at
                    ) VALUES (?, 0, ?, ?, ?, ?)
                    """, incidentId, interval.toSeconds(), timestamp(firstRecheckAt),
                    timestamp(updatedAt), timestamp(updatedAt));
        } catch (DuplicateKeyException concurrentInsert) {
            // 并发窗口已经为同一案件建好计划，只刷新周期，不重置已经完成的次数。
            refreshSchedule(incidentId, interval, firstRecheckAt, updatedAt);
        }
    }

    /**
     * 下一次复查时间是最新 nextTransmissionAt 加现有宽限。
     * 配置从每天改成每周时，用新时间替换还没到期的计划。
     * 这一轮已经到期时保留它，避免新漏传把当天复查推掉。
     * 已经停掉的计划不重新打开。三次复查用完后只接收新漏传，不重新排复查。
     */
    private int refreshSchedule(
            String incidentId,
            Duration interval,
            Instant firstRecheckAt,
            Instant updatedAt
    ) {
        return jdbcTemplate.update("""
                UPDATE scheduled_miss_recheck
                   SET interval_seconds = ?,
                       next_recheck_at = CASE
                           WHEN recheck_count >= 3 OR next_recheck_at IS NULL THEN NULL
                           WHEN next_recheck_at <= ? THEN next_recheck_at
                           ELSE ?
                       END,
                       updated_at = ?
                 WHERE incident_id = ?
                """, interval.toSeconds(), timestamp(updatedAt), timestamp(firstRecheckAt),
                timestamp(updatedAt), incidentId);
    }

    @Override
    public List<ScheduledMissRecheckPlan> lockDuePlans(Instant now, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        String lock = postgres ? " FOR UPDATE OF recheck SKIP LOCKED" : " FOR UPDATE";
        return jdbcTemplate.query("""
                SELECT recheck.incident_id, recheck.recheck_count,
                       recheck.interval_seconds, recheck.next_recheck_at, incident.profile_id
                  FROM scheduled_miss_recheck recheck
                  JOIN incident ON incident.incident_id = recheck.incident_id
                 WHERE recheck.next_recheck_at IS NOT NULL
                   AND recheck.next_recheck_at <= ?
                   AND recheck.recheck_count < 3
                   AND incident.status = 'DETECTED'
                   AND NOT EXISTS (
                       SELECT 1 FROM agent_run run
                        WHERE run.incident_id = recheck.incident_id
                          AND run.active_slot IS NOT NULL)
                 ORDER BY recheck.next_recheck_at, recheck.incident_id
                 LIMIT ?
                """ + lock, this::mapPlan, timestamp(now), limit);
    }

    @Override
    public void markQueued(ScheduledMissRecheckPlan plan, Instant updatedAt) {
        int nextCount = plan.recheckCount() + 1;
        Instant nextAt = null;
        if (nextCount < 3) {
            Instant cadenceAt = plan.nextRecheckAt().plus(plan.interval());
            // Worker 停机很久时不连续补跑三次；从本次实际复查后再等一个设备周期。
            nextAt = cadenceAt.isAfter(updatedAt) ? cadenceAt : updatedAt.plus(plan.interval());
        }
        int updated = jdbcTemplate.update("""
                UPDATE scheduled_miss_recheck
                   SET recheck_count = ?, next_recheck_at = ?, updated_at = ?
                 WHERE incident_id = ? AND recheck_count = ? AND next_recheck_at = ?
                """, nextCount, timestamp(nextAt), timestamp(updatedAt), plan.incidentId(),
                plan.recheckCount(), timestamp(plan.nextRecheckAt()));
        if (updated != 1) {
            throw new IllegalStateException("SCHEDULED_MISS_RECHECK_PLAN_CONFLICT");
        }
    }

    @Override
    public Optional<ScheduledMissRecheckPlan> findByIncidentId(String incidentId) {
        return jdbcTemplate.query("""
                SELECT recheck.incident_id, recheck.recheck_count,
                       recheck.interval_seconds, recheck.next_recheck_at, incident.profile_id
                  FROM scheduled_miss_recheck recheck
                  JOIN incident ON incident.incident_id = recheck.incident_id
                 WHERE recheck.incident_id = ?
                """, this::mapPlan, incidentId).stream().findFirst();
    }

    @Override
    public void stop(String incidentId, Instant updatedAt) {
        jdbcTemplate.update("""
                UPDATE scheduled_miss_recheck
                   SET next_recheck_at = NULL, updated_at = ?
                 WHERE incident_id = ?
                """, timestamp(updatedAt), incidentId);
    }

    private ScheduledMissRecheckPlan mapPlan(ResultSet rs, int rowNum) throws SQLException {
        return new ScheduledMissRecheckPlan(
                rs.getString("incident_id"), rs.getInt("recheck_count"),
                Duration.ofSeconds(rs.getLong("interval_seconds")),
                nullableInstant(rs, "next_recheck_at"), rs.getString("profile_id"));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
