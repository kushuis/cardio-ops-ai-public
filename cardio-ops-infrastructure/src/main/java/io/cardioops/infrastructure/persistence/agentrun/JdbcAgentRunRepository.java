package io.cardioops.infrastructure.persistence.agentrun;

import io.cardioops.application.agentrun.AgentRunStore;
import io.cardioops.application.agentrun.AgentRunClaimPolicy;
import io.cardioops.domain.agent.AgentRun;
import io.cardioops.domain.agent.AgentRunStatus;
import io.cardioops.infrastructure.persistence.JdbcProduct;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 领取用一条 SQL：排队的，或者租约已经过期的 RUNNING，SKIP LOCKED 锁住一行，改成新的 token 并增加 attempt。
 * 完成和失败的更新必须带上当前 token。旧 Worker 的 token 对不上，就改不了，不能覆盖新主人。
 * 续租还要求租约还没过期。H2 分支只给单线程测试，没有 SKIP LOCKED。
 * attempt 大于 1 是在接上次没跑完的执行。图里的 Checkpoint 负责从已落盘节点续跑，那不是人工批准。
 *
 *
 * 两个 Worker 会选中同一行。SKIP LOCKED 让第二条去拿下一行，而不是等这行锁释放后再抢同一次执行。
 */
public final class JdbcAgentRunRepository implements AgentRunStore {

    private static final String RUN_COLUMNS = """
            run_id, incident_id, trigger_event_id, graph_thread_id, run_status,
            profile_id, trace_id, attempt_count, lease_owner, lease_token, lease_expires_at,
            created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final boolean postgres;

    public JdbcAgentRunRepository(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.postgres = JdbcProduct.isPostgres(jdbcTemplate);
    }

    @Override
    /**
     * 候选是 QUEUED，或租约到期的 RUNNING，并且还没到最大尝试次数。领到就换新 token。过期的旧 token 此后写不进完成或失败。
     */
    public Optional<AgentRun> claimNextRunnableRun(AgentRunClaimPolicy policy) {
        Objects.requireNonNull(policy, "policy must not be null");
        Instant now = clock.instant();
        Timestamp nowValue = timestamp(now);
        Timestamp leaseUntil = timestamp(now.plus(policy.leaseDuration()));
        String leaseToken = UUID.randomUUID().toString();

        // 达到恢复上限的崩溃任务必须进入明确终态，不能永远留在 RUNNING 队列里。
        jdbcTemplate.update("""
                UPDATE agent_run
                   SET run_status = 'FAILED', active_slot = NULL,
                       failure_code = 'LEASE_ATTEMPTS_EXHAUSTED',
                       lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                       updated_at = ?
                 WHERE run_status = 'RUNNING'
                   AND lease_expires_at <= ?
                   AND attempt_count >= ?
                """, nowValue, nowValue, policy.maxAttempts());

        if (postgres) {
            // QUEUED 和租约过期的 RUNNING 共用一次 SKIP LOCKED 原子领取。leaseToken 每次都变化，
            // 即使旧 Worker 之后恢复，也无法再提交这一轮已经过期的执行结果。
            List<AgentRun> claimed = jdbcTemplate.query("""
                    WITH candidate AS (
                        SELECT run_id
                          FROM agent_run
                         WHERE attempt_count < ?
                           AND (run_status = 'QUEUED'
                                OR (run_status = 'RUNNING' AND lease_expires_at <= ?))
                         ORDER BY created_at, run_id
                         FOR UPDATE SKIP LOCKED
                         LIMIT 1
                    )
                    UPDATE agent_run
                       SET run_status = 'RUNNING', attempt_count = attempt_count + 1,
                           lease_owner = ?, lease_token = ?, lease_expires_at = ?, updated_at = ?
                     WHERE run_id = (SELECT run_id FROM candidate)
                    RETURNING %s
                    """.formatted(RUN_COLUMNS), this::mapRun,
                    policy.maxAttempts(), nowValue, policy.workerId(), leaseToken, leaseUntil, nowValue);
            return claimed.stream().findFirst();
        }

        // H2 只承担单线程仓储测试；生产并发语义由上面的 PostgreSQL SKIP LOCKED 保证。
        List<String> candidates = jdbcTemplate.queryForList("""
                SELECT run_id FROM agent_run
                 WHERE attempt_count < ?
                   AND (run_status = 'QUEUED'
                        OR (run_status = 'RUNNING' AND lease_expires_at <= ?))
                 ORDER BY created_at, run_id LIMIT 1
                """, String.class, policy.maxAttempts(), nowValue);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        String runId = candidates.getFirst();
        int updated = jdbcTemplate.update("""
                UPDATE agent_run
                   SET run_status = 'RUNNING', attempt_count = attempt_count + 1,
                       lease_owner = ?, lease_token = ?, lease_expires_at = ?, updated_at = ?
                 WHERE run_id = ? AND attempt_count < ?
                   AND (run_status = 'QUEUED'
                        OR (run_status = 'RUNNING' AND lease_expires_at <= ?))
                """, policy.workerId(), leaseToken, leaseUntil, nowValue,
                runId, policy.maxAttempts(), nowValue);
        return updated == 1 ? findRun(runId) : Optional.empty();
    }

    @Override
    public Optional<AgentRun> findRun(String runId) {
        return jdbcTemplate.query(
                "SELECT " + RUN_COLUMNS + " FROM agent_run WHERE run_id = ?",
                this::mapRun, runId).stream().findFirst();
    }

    @Override
    public boolean renewLease(String runId, String leaseToken, Duration leaseDuration) {
        Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
        Instant now = clock.instant();
        int updated = jdbcTemplate.update("""
                UPDATE agent_run
                   SET lease_expires_at = ?, updated_at = ?
                 WHERE run_id = ? AND run_status = 'RUNNING' AND lease_token = ?
                   AND lease_expires_at > ?
                """, timestamp(now.plus(leaseDuration)), timestamp(now), runId, leaseToken,
                timestamp(now));
        return updated == 1;
    }

    @Override
    public void markCompleted(String runId, String leaseToken) {
        int updated = jdbcTemplate.update("""
                UPDATE agent_run SET run_status = 'COMPLETED', active_slot = NULL, failure_code = NULL,
                       lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL, updated_at = ?
                 WHERE run_id = ? AND run_status = 'RUNNING' AND lease_token = ?
                """, timestamp(clock.instant()), runId, leaseToken);
        if (updated != 1) {
            throw new IllegalStateException("AGENT_RUN_COMPLETE_CONFLICT");
        }
    }

    @Override
    public void markFailed(String runId, String leaseToken, String errorCode) {
        int updated = jdbcTemplate.update("""
                UPDATE agent_run
                   SET run_status = 'FAILED', active_slot = NULL, failure_code = ?,
                       lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL, updated_at = ?
                 WHERE run_id = ? AND run_status = 'RUNNING' AND lease_token = ?
                """, errorCode, timestamp(clock.instant()), runId, leaseToken);
        if (updated != 1) {
            throw new IllegalStateException("AGENT_RUN_FAIL_CONFLICT");
        }
    }

    private AgentRun mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new AgentRun(
                rs.getString("run_id"), rs.getString("incident_id"),
                rs.getString("trigger_event_id"), rs.getString("graph_thread_id"),
                AgentRunStatus.valueOf(rs.getString("run_status")), rs.getString("profile_id"),
                rs.getString("trace_id"), rs.getInt("attempt_count"),
                rs.getString("lease_owner"), rs.getString("lease_token"),
                nullableInstant(rs, "lease_expires_at"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
