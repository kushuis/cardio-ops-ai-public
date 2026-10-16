package io.cardioops.infrastructure.persistence;

import io.cardioops.application.integrationevent.IntegrationEventCommand;
import io.cardioops.application.integrationevent.IntegrationInboxPort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;

/**
 * event_id 主键是第一道幂等。插入成功才表示这个信封第一次被占下。已经存在就返回 false，调用方跳过。
 */
public final class JdbcIntegrationInboxRepository implements IntegrationInboxPort {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final boolean postgres;

    public JdbcIntegrationInboxRepository(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.postgres = JdbcProduct.isPostgres(jdbcTemplate);
    }

    @Override
    public boolean tryRegister(IntegrationEventCommand command) {
        String sql = """
                    INSERT INTO event_inbox(
                        event_id, schema_version, event_type, trace_id, device_ref, occurred_at, received_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """ + (postgres ? " ON CONFLICT (event_id) DO NOTHING" : "");
        try {
            int inserted = jdbcTemplate.update(sql,
                        command.eventId(),
                        command.schemaVersion(),
                        command.eventType().name(),
                        command.traceId(),
                        command.deviceRef(),
                        Timestamp.from(command.occurredAt()),
                        Timestamp.from(clock.instant()));
            // PostgreSQL 不通过捕获唯一键异常去重：约束异常会让当前事务进入 aborted 状态。
            return inserted == 1;
        } catch (DuplicateKeyException duplicate) {
            // 仅供 H2 兼容性测试；生产 PostgreSQL 永远走 ON CONFLICT 分支。
            if (!postgres) {
                return false;
            }
            throw duplicate;
        }
    }

    @Override
    public void markProcessed(String eventId, String processingResult) {
        int updated = jdbcTemplate.update("""
                UPDATE event_inbox
                   SET processed_at = ?, processing_result = ?
                 WHERE event_id = ?
                """, Timestamp.from(clock.instant()), processingResult, eventId);
        if (updated != 1) {
            throw new IllegalStateException("inbox event not found: " + eventId);
        }
    }
}
