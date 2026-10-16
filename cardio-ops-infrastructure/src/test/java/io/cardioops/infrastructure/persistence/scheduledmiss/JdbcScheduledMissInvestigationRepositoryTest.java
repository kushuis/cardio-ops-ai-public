package io.cardioops.infrastructure.persistence.scheduledmiss;

import io.cardioops.application.scheduledmiss.ScheduledMissAgentPolicySnapshot;
import io.cardioops.application.agentrun.AgentToolCallAudit;
import io.cardioops.application.agentrun.AgentToolCallStatus;
import io.cardioops.domain.scheduledmiss.IcmProtocolGeneration;
import io.cardioops.domain.scheduledmiss.ScheduledMissFact;
import io.cardioops.domain.scheduledmiss.ScheduledMissInvestigationResult;
import io.cardioops.domain.scheduledmiss.ScheduledMissHandlingRecommendation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证应传未传专用仓储只持久化策略冻结、Tool 审计和业务结果。 */
class JdbcScheduledMissInvestigationRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-06T03:00:00Z");
    private JdbcTemplate jdbc;
    private JdbcScheduledMissInvestigationRepository repository;

    @BeforeEach
    void createDatabase() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:schema-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/integration-intake-schema.sql"),
                new ClassPathResource("db/incident-projection-schema.sql"),
                new ClassPathResource("db/investigation-audit-schema.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcScheduledMissInvestigationRepository(jdbc, new ObjectMapper());
        insertIncidentAndQueuedRun();
    }

    @Test
    void keepsFirstPolicyAndPersistsAuditableResult() {
        repository.savePolicySnapshotIfAbsent(snapshot("prompt-v1"));
        repository.savePolicySnapshotIfAbsent(snapshot("prompt-must-not-overwrite"));
        assertThat(jdbc.queryForObject(
                "SELECT prompt_version FROM agent_policy_snapshot WHERE run_id = 'run-1'", String.class))
                .isEqualTo("prompt-v1");
        assertThat(repository.findPolicySnapshot("run-1").orElseThrow())
                .satisfies(value -> {
                    assertThat(value.deviceProfileVersion()).isEqualTo("device-profiles-v1");
                    assertThat(value.knowledgeRevisionKeys()).containsExactly("playbook@1.0.0");
                });

        repository.appendToolCall(new AgentToolCallAudit(
                "audit-1", "run-1", "incident-1", "get_scheduled_miss_context",
                AgentToolCallStatus.SUCCEEDED, "digest", 1, false, null, NOW, NOW));
        repository.saveResult(result());

        assertThat(repository.findResult("run-1").orElseThrow()).isEqualTo(result());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_tool_call WHERE run_id = 'run-1'", Integer.class))
                .isEqualTo(1);
    }

    private ScheduledMissAgentPolicySnapshot snapshot(String promptVersion) {
        return new ScheduledMissAgentPolicySnapshot(
                "run-1", "demo-icm-v1", "device-profiles-v1",
                IcmProtocolGeneration.V1, "test-model",
                promptVersion, "readonly-v1", List.of("get_scheduled_miss_context"),
                List.of("playbook@1.0.0"), NOW);
    }

    private ScheduledMissInvestigationResult result() {
        return new ScheduledMissInvestigationResult(
                "run-1", "incident-1", true, true, true, false, false,
                ScheduledMissHandlingRecommendation.NO_CONTACT_NEEDED, List.of("tx-1"),
                List.of(new ScheduledMissFact("tx-1", "发现 V1 定时广播")),
                List.of(), List.of(), List.of(), "自动唤醒证据充分", NOW);
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
