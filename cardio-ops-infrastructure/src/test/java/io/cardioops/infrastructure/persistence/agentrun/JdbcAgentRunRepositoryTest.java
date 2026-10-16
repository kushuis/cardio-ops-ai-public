package io.cardioops.infrastructure.persistence.agentrun;

import io.cardioops.application.agentrun.AgentRunClaimPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证公共 AgentRun 仓储不依赖任何具体调查类型也能完成完整生命周期。 */
class JdbcAgentRunRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-06T03:00:00Z");
    private JdbcTemplate jdbc;
    private JdbcAgentRunRepository repository;

    @BeforeEach
    void createDatabase() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:agent-run-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/integration-intake-schema.sql"),
                new ClassPathResource("db/incident-projection-schema.sql"),
                new ClassPathResource("db/investigation-audit-schema.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcAgentRunRepository(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
        insertIncidentAndQueuedRun();
    }

    @Test
    void claimsQueuedRunOnceAndMarksItCompleted() {
        var claimed = repository.claimNextRunnableRun(policy("worker-1", 3)).orElseThrow();

        assertThat(claimed.status().name()).isEqualTo("RUNNING");
        assertThat(claimed.executionAttempt()).isEqualTo(1);
        assertThat(claimed.leaseOwner()).isEqualTo("worker-1");
        assertThat(claimed.leaseToken()).isNotBlank();
        assertThat(repository.claimNextRunnableRun(policy("worker-2", 3))).isEmpty();

        repository.markCompleted("run-1", claimed.leaseToken());

        var completed = repository.findRun("run-1").orElseThrow();
        assertThat(completed.status().name()).isEqualTo("COMPLETED");
        assertThat(completed.leaseToken()).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT active_slot FROM agent_run WHERE run_id = 'run-1'", String.class)).isNull();
    }

    @Test
    void reclaimsExpiredRunWithNewLeaseTokenAndHigherAttempt() {
        var first = repository.claimNextRunnableRun(policy("worker-1", 3)).orElseThrow();
        var laterRepository = new JdbcAgentRunRepository(
                jdbc, Clock.fixed(NOW.plus(Duration.ofMinutes(2)), ZoneOffset.UTC));

        var recovered = laterRepository.claimNextRunnableRun(policy("worker-2", 3)).orElseThrow();

        assertThat(recovered.executionAttempt()).isEqualTo(2);
        assertThat(recovered.recoveredExecution()).isTrue();
        assertThat(recovered.leaseOwner()).isEqualTo("worker-2");
        assertThat(recovered.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThatThrownBy(() -> repository.markCompleted("run-1", first.leaseToken()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AGENT_RUN_COMPLETE_CONFLICT");
    }

    @Test
    void movesExpiredRunToFailedWhenRecoveryAttemptsAreExhausted() {
        repository.claimNextRunnableRun(policy("worker-1", 1)).orElseThrow();
        var laterRepository = new JdbcAgentRunRepository(
                jdbc, Clock.fixed(NOW.plus(Duration.ofMinutes(2)), ZoneOffset.UTC));

        assertThat(laterRepository.claimNextRunnableRun(policy("worker-2", 1))).isEmpty();

        assertThat(laterRepository.findRun("run-1").orElseThrow().status().name()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT failure_code FROM agent_run WHERE run_id = 'run-1'", String.class))
                .isEqualTo("LEASE_ATTEMPTS_EXHAUSTED");
    }

    @Test
    void renewsOnlyTheCurrentLeaseToken() {
        var claimed = repository.claimNextRunnableRun(policy("worker-1", 3)).orElseThrow();

        assertThat(repository.renewLease("run-1", "obsolete-token", Duration.ofMinutes(1))).isFalse();
        assertThat(repository.renewLease("run-1", claimed.leaseToken(), Duration.ofMinutes(2))).isTrue();
        assertThat(repository.findRun("run-1").orElseThrow().leaseExpiresAt())
                .isEqualTo(NOW.plus(Duration.ofMinutes(2)));
    }

    @Test
    void expiredOwnerCannotResurrectItsLeaseBeforeAnotherWorkerClaimsIt() {
        var claimed = repository.claimNextRunnableRun(policy("worker-1", 3)).orElseThrow();
        var expiredOwner = new JdbcAgentRunRepository(
                jdbc, Clock.fixed(NOW.plus(Duration.ofMinutes(2)), ZoneOffset.UTC));

        assertThat(expiredOwner.renewLease(
                "run-1", claimed.leaseToken(), Duration.ofMinutes(1))).isFalse();
        assertThat(repository.findRun("run-1").orElseThrow().leaseExpiresAt())
                .isEqualTo(NOW.plus(Duration.ofMinutes(1)));
    }

    private static AgentRunClaimPolicy policy(String workerId, int maxAttempts) {
        return new AgentRunClaimPolicy(workerId, Duration.ofMinutes(1), maxAttempts);
    }

    private void insertIncidentAndQueuedRun() {
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
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "run-1", "incident-1", "event-1", "thread-1", "QUEUED",
                "demo-icm-v1", "trace-1", "incident-1", now, now);
    }
}
