package io.cardioops.infrastructure.persistence.customerservice;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证第五章上线时，已经完成的历史调查能够幂等进入待人工复核。 */
class CustomerServiceSchemaMigrationTest {

    @Test
    void backfillsCompletedScheduledMissWithoutTouchingItTwice() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:schema-" + System.nanoTime()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        var beforeCurrentSchema = new ResourceDatabasePopulator(
                new ClassPathResource("db/integration-intake-schema.sql"),
                new ClassPathResource("db/incident-projection-schema.sql"),
                new ClassPathResource("db/investigation-audit-schema.sql"));
        beforeCurrentSchema.execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insertCompletedLegacyCase(jdbc);

        var schema = new ResourceDatabasePopulator(new ClassPathResource("db/customer-service-schema.sql"));
        schema.execute(dataSource);
        schema.execute(dataSource);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM incident WHERE incident_id = 'incident-old'", String.class))
                .isEqualTo("PENDING_HUMAN_REVIEW");
        assertThat(jdbc.queryForObject(
                "SELECT version FROM incident WHERE incident_id = 'incident-old'", Long.class))
                .isEqualTo(1L);
    }

    private void insertCompletedLegacyCase(JdbcTemplate jdbc) {
        Timestamp detected = Timestamp.from(Instant.parse("2026-09-01T00:00:00Z"));
        Timestamp completed = Timestamp.from(Instant.parse("2026-09-01T00:05:00Z"));
        jdbc.update("""
                INSERT INTO incident(
                    incident_id, incident_key, device_ref, device_family, model_code,
                    firmware_version, firmware_version_number, incident_type, severity, status,
                    correlation_ref, detected_at, last_evidence_at, profile_status, profile_id,
                    version, created_at, updated_at, resolved_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "incident-old", "key-old", "device-old", "ICM", "DEMO_ICM_V1", "1.2.0", 120L,
                "SCHEDULED_GATEWAY_TRANSMISSION_MISSED", "UNASSESSED", "DETECTED",
                "MONITORING:device-old:1", detected, detected, "SUPPORTED", "demo-icm-v1",
                0L, detected, detected, null);
        jdbc.update("""
                INSERT INTO agent_run(
                    run_id, incident_id, trigger_event_id, graph_thread_id, run_status,
                    profile_id, trace_id, active_slot, created_at, updated_at, failure_code
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "run-old", "incident-old", "event-old", "thread-old", "COMPLETED",
                "demo-icm-v1", "trace-old", null, detected, completed, null);
        jdbc.update("""
                INSERT INTO investigation_result(
                    run_id, incident_id, result_json, recovered, review_recommendation, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, "run-old", "incident-old", "{}", true, "NO_CONTACT_NEEDED", completed);
    }
}
