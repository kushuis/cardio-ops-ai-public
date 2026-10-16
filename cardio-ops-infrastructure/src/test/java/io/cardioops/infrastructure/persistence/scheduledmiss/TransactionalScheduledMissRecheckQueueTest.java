package io.cardioops.infrastructure.persistence.scheduledmiss;

import io.cardioops.infrastructure.persistence.JdbcIncidentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** 到期复查排成新的 AgentRun 和新图线程，不续跑已经结束的调查。 */
class TransactionalScheduledMissRecheckQueueTest {

    private static final Instant NOW = Instant.parse("2026-09-06T01:03:00Z");

    private JdbcTemplate jdbc;
    private JdbcScheduledMissRecheckRepository recheckRepository;
    private TransactionalScheduledMissRecheckQueue queue;

    @BeforeEach
    void createDatabase() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:recheck-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/integration-intake-schema.sql"),
                new ClassPathResource("db/incident-projection-schema.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE scheduled_miss_recheck (
                    incident_id VARCHAR(36) PRIMARY KEY,
                    recheck_count INTEGER NOT NULL DEFAULT 0,
                    interval_seconds BIGINT NOT NULL,
                    next_recheck_at TIMESTAMP WITH TIME ZONE,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL)
                """);
        recheckRepository = new JdbcScheduledMissRecheckRepository(jdbc);
        var incidentRepository = new JdbcIncidentRepository(jdbc, new ObjectMapper());
        queue = new TransactionalScheduledMissRecheckQueue(
                recheckRepository, incidentRepository,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        insertOpenCase();
    }

    @Test
    void duePlanQueuesANewRunOnANewGraphThread() {
        assertThat(queue.queueDueRechecks(10)).isEqualTo(1);

        var threadId = jdbc.queryForObject("""
                SELECT graph_thread_id FROM agent_run
                 WHERE incident_id = 'incident-1' AND run_status = 'QUEUED'
                """, String.class);
        var plan = recheckRepository.findByIncidentId("incident-1").orElseThrow();
        assertThat(threadId).isEqualTo("incident:incident-1:recheck:1");
        assertThat(threadId).isNotEqualTo("incident:incident-1:initial");
        assertThat(plan.recheckCount()).isEqualTo(1);
        assertThat(plan.nextRecheckAt()).isEqualTo(NOW.plusSeconds(86_400));
        assertThat(queue.queueDueRechecks(10)).isZero();
    }

    @Test
    void activeRunBlocksAnotherRecheckUntilItFinishes() {
        jdbc.update("""
                INSERT INTO agent_run(
                    run_id, incident_id, trigger_event_id, graph_thread_id, run_status,
                    profile_id, trace_id, active_slot, created_at, updated_at
                ) VALUES ('run-active', 'incident-1', 'event-active',
                          'incident:incident-1:initial', 'RUNNING',
                          'demo-icm-v1', 'trace-active', 'incident-1', ?, ?)
                """, Timestamp.from(NOW), Timestamp.from(NOW));

        assertThat(queue.queueDueRechecks(10)).isZero();
        assertThat(recheckRepository.findByIncidentId("incident-1").orElseThrow().recheckCount()).isZero();
    }

    @Test
    void thirdRecheckIsTheLastAutomaticRun() {
        jdbc.update("""
                UPDATE scheduled_miss_recheck
                   SET recheck_count = 2
                 WHERE incident_id = 'incident-1'
                """);

        assertThat(queue.queueDueRechecks(10)).isEqualTo(1);

        var plan = recheckRepository.findByIncidentId("incident-1").orElseThrow();
        assertThat(plan.recheckCount()).isEqualTo(3);
        assertThat(plan.nextRecheckAt()).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT graph_thread_id FROM agent_run
                 WHERE incident_id = 'incident-1' AND run_status = 'QUEUED'
                """, String.class)).isEqualTo("incident:incident-1:recheck:3");
    }

    @Test
    void humanFollowUpIsNotQueuedAgain() {
        jdbc.update("UPDATE incident SET status = 'FOLLOW_UP_REQUIRED' WHERE incident_id = 'incident-1'");

        assertThat(queue.queueDueRechecks(10)).isZero();
    }

    private void insertOpenCase() {
        Timestamp now = Timestamp.from(NOW);
        jdbc.update("""
                INSERT INTO incident(
                    incident_id, incident_key, device_ref, device_family, model_code,
                    firmware_version, firmware_version_number, incident_type, severity, status,
                    correlation_ref, detected_at, last_evidence_at, profile_status, profile_id,
                    version, created_at, updated_at, resolved_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "incident-1", "key-1", "device-1", "ICM", "DEMO_ICM_V1", "1.2.0", 120L,
                "SCHEDULED_GATEWAY_TRANSMISSION_MISSED", "UNASSESSED", "DETECTED",
                "MONITORING:device-1:1788656400", now, now, "SUPPORTED", "demo-icm-v1",
                0L, now, now, null);
        jdbc.update("""
                INSERT INTO agent_run(
                    run_id, incident_id, trigger_event_id, graph_thread_id, run_status,
                    profile_id, trace_id, active_slot, created_at, updated_at
                ) VALUES ('run-initial', 'incident-1', 'event-initial',
                          'incident:incident-1:initial', 'COMPLETED',
                          'demo-icm-v1', 'trace-initial', NULL, ?, ?)
                """, now, now);
        jdbc.update("""
                INSERT INTO scheduled_miss_recheck(
                    incident_id, recheck_count, interval_seconds,
                    next_recheck_at, created_at, updated_at
                ) VALUES ('incident-1', 0, 86400, ?, ?, ?)
                """, now, now, now);
    }
}
