package io.cardioops.infrastructure.persistence.scheduledmiss;

import io.cardioops.application.agentrun.AgentRunStore;
import io.cardioops.domain.agent.AgentRun;
import io.cardioops.domain.scheduledmiss.ScheduledMissFact;
import io.cardioops.domain.scheduledmiss.ScheduledMissHandlingRecommendation;
import io.cardioops.domain.scheduledmiss.ScheduledMissInvestigationResult;
import io.cardioops.infrastructure.persistence.agentrun.JdbcAgentRunRepository;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证业务结果和公共 Run 状态必须一起提交或一起回滚。 */
class TransactionalScheduledMissInvestigationCompletionTest {

    private static final Instant NOW = Instant.parse("2026-09-06T03:00:00Z");
    private JdbcTemplate jdbc;
    private JdbcAgentRunRepository agentRunRepository;
    private JdbcIncidentRepository incidentRepository;
    private JdbcScheduledMissInvestigationRepository investigationRepository;
    private JdbcScheduledMissRecheckRepository recheckRepository;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void createDatabase() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:completion-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/integration-intake-schema.sql"),
                new ClassPathResource("db/incident-projection-schema.sql"),
                new ClassPathResource("db/investigation-audit-schema.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        agentRunRepository = new JdbcAgentRunRepository(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
        incidentRepository = new JdbcIncidentRepository(jdbc, new ObjectMapper());
        investigationRepository = new JdbcScheduledMissInvestigationRepository(jdbc, new ObjectMapper());
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
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        insertIncidentAndRunningRun();
    }

    @Test
    void commitsResultAndRunCompletionTogether() {
        var useCase = new TransactionalScheduledMissInvestigationCompletion(
                investigationRepository, agentRunRepository, incidentRepository,
                recheckRepository, transactionTemplate);

        useCase.complete("run-1", "lease-1", recoveredResult());

        assertThat(investigationRepository.findResult("run-1")).contains(recoveredResult());
        assertThat(agentRunRepository.findRun("run-1").orElseThrow().status().name())
                .isEqualTo("COMPLETED");
        assertThat(incidentRepository.findById("incident-1").orElseThrow().status().name())
                .isEqualTo("PENDING_HUMAN_REVIEW");
        assertThat(recheckRepository.findByIncidentId("incident-1").orElseThrow().nextRecheckAt())
                .isNull();
    }

    @Test
    void thirdUnrecoveredRecheckStopsSchedulingAndRequiresManualFollowUp() {
        jdbc.update("""
                UPDATE scheduled_miss_recheck
                   SET recheck_count = 3
                 WHERE incident_id = 'incident-1'
                """);
        var useCase = new TransactionalScheduledMissInvestigationCompletion(
                investigationRepository, agentRunRepository, incidentRepository,
                recheckRepository, transactionTemplate);

        useCase.complete("run-1", "lease-1", unrecoveredResult());

        assertThat(incidentRepository.findById("incident-1").orElseThrow().status().name())
                .isEqualTo("FOLLOW_UP_REQUIRED");
        assertThat(recheckRepository.findByIncidentId("incident-1").orElseThrow().nextRecheckAt())
                .isNull();
    }

    @Test
    void missingRecheckPlanStillCompletesWithoutFurtherAutomaticRecheck() {
        jdbc.update("DELETE FROM scheduled_miss_recheck WHERE incident_id = 'incident-1'");
        var useCase = new TransactionalScheduledMissInvestigationCompletion(
                investigationRepository, agentRunRepository, incidentRepository,
                recheckRepository, transactionTemplate);

        useCase.complete("run-1", "lease-1", unrecoveredResult());

        assertThat(investigationRepository.findResult("run-1")).isPresent();
        assertThat(agentRunRepository.findRun("run-1").orElseThrow().status().name())
                .isEqualTo("COMPLETED");
        assertThat(incidentRepository.findById("incident-1").orElseThrow().status().name())
                .isEqualTo("FOLLOW_UP_REQUIRED");
        assertThat(recheckRepository.findByIncidentId("incident-1")).isEmpty();
    }

    @Test
    void missingRecheckPlanStillRecordsRecoveryForClosureConfirmation() {
        jdbc.update("DELETE FROM scheduled_miss_recheck WHERE incident_id = 'incident-1'");
        var useCase = new TransactionalScheduledMissInvestigationCompletion(
                investigationRepository, agentRunRepository, incidentRepository,
                recheckRepository, transactionTemplate);

        useCase.complete("run-1", "lease-1", recoveredResult());

        assertThat(incidentRepository.findById("incident-1").orElseThrow().status().name())
                .isEqualTo("PENDING_HUMAN_REVIEW");
        assertThat(recheckRepository.findByIncidentId("incident-1")).isEmpty();
    }

    @Test
    void unrecoveredInvestigationKeepsAutomaticTrackingBeforeThirdRecheck() {
        var useCase = new TransactionalScheduledMissInvestigationCompletion(
                investigationRepository, agentRunRepository, incidentRepository,
                recheckRepository, transactionTemplate);

        useCase.complete("run-1", "lease-1", unrecoveredResult());

        assertThat(incidentRepository.findById("incident-1").orElseThrow().status().name())
                .isEqualTo("DETECTED");
        assertThat(recheckRepository.findByIncidentId("incident-1").orElseThrow().nextRecheckAt())
                .isNotNull();
    }

    @Test
    void rollsBackResultWhenRunCannotBeCompleted() {
        AgentRunStore failingRunStore = new AgentRunStore() {
            @Override public Optional<AgentRun> claimNextRunnableRun(io.cardioops.application.agentrun.AgentRunClaimPolicy policy) { return Optional.empty(); }
            @Override public Optional<AgentRun> findRun(String runId) { return Optional.empty(); }
            @Override public boolean renewLease(String runId, String leaseToken, java.time.Duration leaseDuration) { return false; }
            @Override public void markCompleted(String runId, String leaseToken) { throw new IllegalStateException("SIMULATED_FAILURE"); }
            @Override public void markFailed(String runId, String leaseToken, String errorCode) { }
        };
        var useCase = new TransactionalScheduledMissInvestigationCompletion(
                investigationRepository, failingRunStore, incidentRepository,
                recheckRepository, transactionTemplate);

        assertThatThrownBy(() -> useCase.complete("run-1", "lease-1", recoveredResult()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SIMULATED_FAILURE");

        assertThat(investigationRepository.findResult("run-1")).isEmpty();
        assertThat(incidentRepository.findById("incident-1").orElseThrow().status().name())
                .isEqualTo("DETECTED");
    }

    private ScheduledMissInvestigationResult recoveredResult() {
        return new ScheduledMissInvestigationResult(
                "run-1", "incident-1", true, true, true, false, false,
                ScheduledMissHandlingRecommendation.NO_CONTACT_NEEDED, List.of("tx-1"),
                List.of(new ScheduledMissFact("tx-1", "发现 V1 定时广播")),
                List.of(), List.of(), List.of(), "自动唤醒证据充分", NOW);
    }

    private ScheduledMissInvestigationResult unrecoveredResult() {
        return new ScheduledMissInvestigationResult(
                "run-1", "incident-1", false, false, false, false, false,
                ScheduledMissHandlingRecommendation.HUMAN_REVIEW, List.of(), List.of(),
                List.of(), List.of(), List.of("未发现符合恢复规则的新证据"),
                "三次复查后仍未恢复，停止自动复查并转人工跟进", NOW);
    }

    private void insertIncidentAndRunningRun() {
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
                INSERT INTO scheduled_miss_recheck(
                    incident_id, recheck_count, interval_seconds,
                    next_recheck_at, created_at, updated_at
                ) VALUES (?, 0, 86400, ?, ?, ?)
                """, "incident-1", Timestamp.from(NOW.plusSeconds(86400)), now, now);
        jdbc.update("""
                INSERT INTO agent_run(
                    run_id, incident_id, trigger_event_id, graph_thread_id, run_status,
                    profile_id, trace_id, active_slot, attempt_count, lease_owner, lease_token,
                    lease_expires_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "run-1", "incident-1", "event-1", "thread-1", "RUNNING",
                "demo-icm-v1", "trace-1", "incident-1", 1, "worker-1", "lease-1",
                Timestamp.from(NOW.plusSeconds(60)), now, now);
    }
}
