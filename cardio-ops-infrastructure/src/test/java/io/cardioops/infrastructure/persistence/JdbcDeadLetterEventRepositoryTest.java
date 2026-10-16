package io.cardioops.infrastructure.persistence;

import io.cardioops.application.deadletter.DeadLetterEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 DLT 的异常摘要不会突破 PostgreSQL 审计表的列长度。 */
class JdbcDeadLetterEventRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private JdbcDeadLetterEventRepository repository;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:dead-letter-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE dead_letter_event (
                    dead_letter_id VARCHAR(128) PRIMARY KEY,
                    original_event_id VARCHAR(128),
                    original_topic VARCHAR(255) NOT NULL,
                    raw_payload TEXT NOT NULL,
                    failure_type VARCHAR(255) NOT NULL,
                    failure_message VARCHAR(1000),
                    failed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    replay_status VARCHAR(32) NOT NULL
                )
                """);
        repository = new JdbcDeadLetterEventRepository(jdbcTemplate);
    }

    @Test
    void savesBoundedFailureMessageWhenKafkaHeaderContainsLongExceptionText() {
        repository.saveIfAbsent(new DeadLetterEvent(
                "cardio-ops.integration-events.v1.DLT:0:0",
                "event-001",
                "cardio-ops.integration-events.v1",
                "{\"eventId\":\"event-001\"}",
                "org.springframework.web.client.HttpServerErrorException",
                "x".repeat(1_001),
                Instant.parse("2026-10-06T19:38:18Z"),
                "PENDING"));

        String failureMessage = jdbcTemplate.queryForObject(
                "SELECT failure_message FROM dead_letter_event WHERE dead_letter_id = ?",
                String.class,
                "cardio-ops.integration-events.v1.DLT:0:0");

        assertThat(failureMessage).isEqualTo("x".repeat(1_000));
    }
}
