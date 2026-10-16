package io.cardioops.infrastructure.persistence;

import io.cardioops.application.deadletter.DeadLetterEvent;
import io.cardioops.application.deadletter.DeadLetterEventPort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 死信表让坏消息能查、能按原样重放。主键是 deadLetterId，审计消费者重复读不会插两行。已重放的更新带条件，不会把状态打回待重放。
 */
public final class JdbcDeadLetterEventRepository implements DeadLetterEventPort {

    /** 与 dead_letter_event.failure_message 的 VARCHAR(1000) 约束保持一致。 */
    private static final int FAILURE_MESSAGE_MAX_LENGTH = 1_000;

    private final JdbcTemplate jdbcTemplate;

    public JdbcDeadLetterEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void saveIfAbsent(DeadLetterEvent event) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO dead_letter_event(
                        dead_letter_id, original_event_id, original_topic, raw_payload,
                        failure_type, failure_message, failed_at, replay_status
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    event.deadLetterId(), event.originalEventId(), event.originalTopic(), event.rawPayload(),
                    event.failureType(), truncateFailureMessage(event.failureMessage()), Timestamp.from(event.failedAt()),
                    event.replayStatus());
        } catch (DuplicateKeyException ignored) {
            // Kafka 的 at-least-once 语义允许 DLT 审计消费者重复收到同一 offset。
        }
    }

    @Override
    public Optional<DeadLetterEvent> find(String deadLetterId) {
        return jdbcTemplate.query("""
                        SELECT dead_letter_id, original_event_id, original_topic, raw_payload,
                               failure_type, failure_message, failed_at, replay_status
                          FROM dead_letter_event
                         WHERE dead_letter_id = ?
                        """,
                (resultSet, rowNumber) -> new DeadLetterEvent(
                        resultSet.getString("dead_letter_id"),
                        resultSet.getString("original_event_id"),
                        resultSet.getString("original_topic"),
                        resultSet.getString("raw_payload"),
                        resultSet.getString("failure_type"),
                        resultSet.getString("failure_message"),
                        resultSet.getTimestamp("failed_at").toInstant(),
                        resultSet.getString("replay_status")),
                deadLetterId).stream().findFirst();
    }

    @Override
    public void markReplayed(String deadLetterId, Instant replayedAt) {
        int updated = jdbcTemplate.update("""
                UPDATE dead_letter_event
                   SET replay_status = 'REPLAYED', replayed_at = ?
                 WHERE dead_letter_id = ? AND replay_status <> 'REPLAYED'
                """, Timestamp.from(replayedAt), deadLetterId);
        if (updated == 0 && find(deadLetterId).isEmpty()) {
            throw new IllegalArgumentException("dead letter not found: " + deadLetterId);
        }
    }

    /**
     * Kafka DLT Header 中的异常信息可能包含嵌套异常或远端响应，长度不可控。
     *
     * <p>DLT 审计不能因为错误摘要超过列长度而再次失败；完整原始事件仍保存在
     * {@code raw_payload}，这里只保留用于运营检索的受限摘要。</p>
     */
    private String truncateFailureMessage(String failureMessage) {
        if (failureMessage == null || failureMessage.length() <= FAILURE_MESSAGE_MAX_LENGTH) {
            return failureMessage;
        }
        return failureMessage.substring(0, FAILURE_MESSAGE_MAX_LENGTH);
    }
}
