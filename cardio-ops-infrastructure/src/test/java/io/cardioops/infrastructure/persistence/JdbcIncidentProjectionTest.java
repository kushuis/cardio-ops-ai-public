package io.cardioops.infrastructure.persistence;

import io.cardioops.application.integrationevent.DetectedIssueType;
import io.cardioops.application.incident.IncidentProjectionService;
import io.cardioops.application.incident.IncidentProjectionStatus;
import io.cardioops.application.incident.IncidentQuery;
import io.cardioops.application.integrationevent.IntegrationEventCommand;
import io.cardioops.application.integrationevent.IntegrationEventDetails;
import io.cardioops.application.integrationevent.IntegrationEventKind;
import io.cardioops.application.integrationevent.IssueDetectedDetails;
import io.cardioops.infrastructure.persistence.scheduledmiss.JdbcScheduledMissRecheckRepository;
import io.cardioops.domain.device.DeviceDescriptor;
import io.cardioops.domain.agent.AgentRunStatus;
import io.cardioops.domain.device.DeviceFamily;
import io.cardioops.domain.incident.EvidenceType;
import io.cardioops.domain.incident.IncidentSeverity;
import io.cardioops.domain.incident.IncidentStatus;
import io.cardioops.domain.incident.IncidentType;
import io.cardioops.domain.device.ProfileResolutionStatus;
import io.cardioops.domain.device.ResolvedInvestigationProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcIncidentProjectionTest {

    private static final Instant EXPECTED_AT = Instant.parse("2026-09-04T00:00:00Z");
    private static final Instant NEXT_AT = Instant.parse("2026-09-05T00:00:00Z");
    private static final String MONITORING_REF = "MONITORING:device-001:1788480000";

    private static final Instant NOW = Instant.parse("2026-09-04T02:00:00Z");
    private static final Duration GRACE = Duration.ofMinutes(3);

    private JdbcTemplate jdbcTemplate;
    private JdbcIncidentRepository repository;
    private JdbcScheduledMissRecheckRepository recheckRepository;
    private IncidentProjectionService projection;

    @BeforeEach
    void createDatabase() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:schema-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/integration-intake-schema.sql"),
                new ClassPathResource("db/incident-projection-schema.sql"),
                new ClassPathResource("db/investigation-audit-schema.sql")).execute(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new JdbcIncidentRepository(jdbcTemplate, new ObjectMapper());
        jdbcTemplate.execute("""
                CREATE TABLE scheduled_miss_recheck (
                    incident_id VARCHAR(36) PRIMARY KEY,
                    recheck_count INTEGER NOT NULL DEFAULT 0,
                    interval_seconds BIGINT NOT NULL,
                    next_recheck_at TIMESTAMP WITH TIME ZONE,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL)
                """);
        recheckRepository = new JdbcScheduledMissRecheckRepository(jdbcTemplate);
        projection = new IncidentProjectionService(
                repository, recheckRepository,
                Clock.fixed(NOW, ZoneOffset.UTC), GRACE);
    }

    @Test
    void scheduledMissedCreatesOneUnassessedIncidentAndQueuedRun() {
        var result = projection.project(issueCommand(
                "event-missed", MONITORING_REF, DetectedIssueType.SCHEDULED_MISSED), profile());

        var incident = repository.findById(result.incidentId()).orElseThrow();
        assertThat(result.status()).isEqualTo(IncidentProjectionStatus.INCIDENT_CREATED);
        assertThat(incident.type()).isEqualTo(IncidentType.SCHEDULED_GATEWAY_TRANSMISSION_MISSED);
        assertThat(incident.severity()).isEqualTo(IncidentSeverity.UNASSESSED);
        assertThat(incident.status()).isEqualTo(IncidentStatus.DETECTED);
        assertThat(repository.listEvidence(incident.incidentId()))
                .singleElement()
                .extracting(evidence -> evidence.type())
                .isEqualTo(EvidenceType.SCHEDULED_MISSED);
        assertThat(repository.listRuns(incident.incidentId())).hasSize(1);
    }

    @Test
    void consecutiveScheduledMissedWindowsCreateIndependentIncidents() {
        var first = projection.project(issueCommand(
                "event-missed-1", MONITORING_REF, DetectedIssueType.SCHEDULED_MISSED), profile());
        var second = projection.project(issueCommand(
                "event-missed-2", "MONITORING:device-001:1788566400",
                DetectedIssueType.SCHEDULED_MISSED), profile());

        assertThat(second.status()).isEqualTo(IncidentProjectionStatus.INCIDENT_CREATED);
        assertThat(second.incidentId()).isNotEqualTo(first.incidentId());
        assertThat(repository.list(new IncidentQuery(0, 20, null, null, null)).total()).isEqualTo(2);
        assertThat(repository.listRuns(first.incidentId())).hasSize(1);
        assertThat(repository.listRuns(second.incidentId())).hasSize(1);
    }

    @Test
    void eachScheduledMissedWindowKeepsItsOwnRecheckPlan() {
        var first = projection.project(scheduledMiss(
                "event-missed-1", MONITORING_REF, EXPECTED_AT, NEXT_AT), profile());
        Instant weeklyNext = NEXT_AT.plus(Duration.ofDays(6));
        var second = projection.project(scheduledMiss(
                "event-missed-2", "MONITORING:device-001:1788566400", NEXT_AT, weeklyNext), profile());

        var firstPlan = recheckRepository.findByIncidentId(first.incidentId()).orElseThrow();
        var secondPlan = recheckRepository.findByIncidentId(second.incidentId()).orElseThrow();
        assertThat(firstPlan.interval()).isEqualTo(Duration.ofDays(1));
        assertThat(firstPlan.nextRecheckAt()).isEqualTo(NEXT_AT.plus(GRACE));
        assertThat(secondPlan.interval()).isEqualTo(Duration.ofDays(6));
        assertThat(secondPlan.nextRecheckAt()).isEqualTo(weeklyNext.plus(GRACE));
    }

    @Test
    void repeatedDeliveryForTheSameWindowReusesIncidentWithoutAnotherRun() {
        var first = projection.project(scheduledMiss(
                "event-missed-1", MONITORING_REF, EXPECTED_AT, NEXT_AT), profile());
        var repeated = projection.project(scheduledMiss(
                "event-missed-2", MONITORING_REF, EXPECTED_AT, NEXT_AT), profile());

        assertThat(repeated.status()).isEqualTo(IncidentProjectionStatus.INCIDENT_ALREADY_EXISTS);
        assertThat(repeated.incidentId()).isEqualTo(first.incidentId());
        assertThat(repeated.agentRunQueued()).isFalse();
        assertThat(repository.list(new IncidentQuery(0, 20, null, null, null)).total()).isEqualTo(1);
        assertThat(repository.listRuns(first.incidentId())).hasSize(1);
    }

    @Test
    void recoveredCaseWaitingForClosureDoesNotAbsorbTheNextWindow() {
        var first = projection.project(scheduledMiss(
                "event-missed-1", MONITORING_REF, EXPECTED_AT, NEXT_AT), profile());
        jdbcTemplate.update(
                "UPDATE incident SET status = 'PENDING_HUMAN_REVIEW' WHERE incident_id = ?",
                first.incidentId());
        jdbcTemplate.update(
                "UPDATE scheduled_miss_recheck SET next_recheck_at = NULL WHERE incident_id = ?",
                first.incidentId());

        var second = projection.project(scheduledMiss(
                "event-missed-2", "MONITORING:device-001:1788566400",
                NEXT_AT, NEXT_AT.plus(Duration.ofDays(1))), profile());

        assertThat(second.status()).isEqualTo(IncidentProjectionStatus.INCIDENT_CREATED);
        assertThat(second.incidentId()).isNotEqualTo(first.incidentId());
        assertThat(second.agentRunQueued()).isTrue();
        assertThat(recheckRepository.findByIncidentId(first.incidentId()).orElseThrow().nextRecheckAt())
                .isNull();
        assertThat(recheckRepository.findByIncidentId(second.incidentId()).orElseThrow().nextRecheckAt())
                .isEqualTo(NEXT_AT.plus(Duration.ofDays(1)).plus(GRACE));
        assertThat(repository.list(new IncidentQuery(0, 20, null, null, null)).total()).isEqualTo(2);
    }

    @Test
    void closedCaseOpensANewScheduledMissAndFirstRun() {
        var first = projection.project(scheduledMiss(
                "event-missed-1", MONITORING_REF, EXPECTED_AT, NEXT_AT), profile());
        jdbcTemplate.update(
                "UPDATE incident SET status = 'CLOSED', resolved_at = ? WHERE incident_id = ?",
                java.sql.Timestamp.from(NOW), first.incidentId());

        var second = projection.project(scheduledMiss(
                "event-missed-2", "MONITORING:device-001:1788566400",
                NEXT_AT, NEXT_AT.plus(Duration.ofDays(1))), profile());

        assertThat(second.status()).isEqualTo(IncidentProjectionStatus.INCIDENT_CREATED);
        assertThat(second.incidentId()).isNotEqualTo(first.incidentId());
        assertThat(second.agentRunQueued()).isTrue();
        assertThat(repository.listRuns(second.incidentId())).hasSize(1);
    }

    @Test
    void exhaustedCaseDoesNotAbsorbTheNextWindow() {
        var first = projection.project(scheduledMiss(
                "event-missed-1", MONITORING_REF, EXPECTED_AT, NEXT_AT), profile());
        jdbcTemplate.update("""
                UPDATE scheduled_miss_recheck
                   SET recheck_count = 3, next_recheck_at = NULL
                 WHERE incident_id = ?
                """, first.incidentId());
        jdbcTemplate.update(
                "UPDATE incident SET status = 'FOLLOW_UP_REQUIRED' WHERE incident_id = ?",
                first.incidentId());

        var second = projection.project(scheduledMiss(
                "event-missed-2", "MONITORING:device-001:1788566400",
                NEXT_AT, NEXT_AT.plus(Duration.ofDays(1))), profile());

        var oldPlan = recheckRepository.findByIncidentId(first.incidentId()).orElseThrow();
        assertThat(second.status()).isEqualTo(IncidentProjectionStatus.INCIDENT_CREATED);
        assertThat(second.incidentId()).isNotEqualTo(first.incidentId());
        assertThat(second.agentRunQueued()).isTrue();
        assertThat(oldPlan.recheckCount()).isEqualTo(3);
        assertThat(oldPlan.nextRecheckAt()).isNull();
        assertThat(repository.listEvidence(first.incidentId())).hasSize(1);
        assertThat(repository.listRuns(first.incidentId())).hasSize(1);
        assertThat(repository.listEvidence(second.incidentId())).hasSize(1);
        assertThat(repository.listRuns(second.incidentId())).hasSize(1);
    }

    @Test
    void unrecoveredManualFollowUpDoesNotAbsorbTheNextWindow() {
        var first = projection.project(scheduledMiss(
                "event-missed-1", MONITORING_REF, EXPECTED_AT, NEXT_AT), profile());
        jdbcTemplate.update("""
                UPDATE agent_run
                   SET run_status = 'COMPLETED', active_slot = NULL
                 WHERE incident_id = ?
                """, first.incidentId());
        jdbcTemplate.update("""
                INSERT INTO investigation_result(
                    run_id, incident_id, result_json, recovered, review_recommendation, completed_at
                )
                SELECT run_id, incident_id, '{}', FALSE, 'HUMAN_REVIEW', ?
                  FROM agent_run WHERE incident_id = ?
                """, java.sql.Timestamp.from(NOW), first.incidentId());
        jdbcTemplate.update(
                "UPDATE incident SET status = 'FOLLOW_UP_REQUIRED' WHERE incident_id = ?",
                first.incidentId());

        var second = projection.project(scheduledMiss(
                "event-missed-2", "MONITORING:device-001:1788566400",
                NEXT_AT, NEXT_AT.plus(Duration.ofDays(1))), profile());

        assertThat(second.status()).isEqualTo(IncidentProjectionStatus.INCIDENT_CREATED);
        assertThat(second.incidentId()).isNotEqualTo(first.incidentId());
        assertThat(second.agentRunQueued()).isTrue();
    }

    @Test
    void lowBatteryCreatesIndependentIncidentAndQueuesItsScenarioAgent() {
        String batteryRef = "BATTERY:device-001:ICM_PATIENT_SERVICE:88";

        var result = projection.project(issueCommand(
                "event-battery", batteryRef, DetectedIssueType.LOW_BATTERY), profile());

        var incident = repository.findById(result.incidentId()).orElseThrow();
        assertThat(incident.type()).isEqualTo(IncidentType.DEVICE_LOW_BATTERY);
        assertThat(incident.status()).isEqualTo(IncidentStatus.DETECTED);
        assertThat(incident.severity()).isEqualTo(IncidentSeverity.UNASSESSED);
        assertThat(repository.listEvidence(incident.incidentId()))
                .singleElement()
                .extracting(evidence -> evidence.type())
                .isEqualTo(EvidenceType.LOW_BATTERY);
        assertThat(repository.listRuns(incident.incidentId())).singleElement()
                .extracting(run -> run.status()).isEqualTo(AgentRunStatus.QUEUED);
    }

    @Test
    void incompleteTransmissionIssueCreatesIndependentIncidentWithoutAgentRun() {
        var details = new IssueDetectedDetails(
                DetectedIssueType.TRANSMISSION_EVENTS_INCOMPLETE, null, null, 10, 6);
        var result = projection.project(command(
                "event-partial", IntegrationEventKind.ISSUE_DETECTED,
                "TRANSMISSION:device-001:record-event-partial", details), profile());

        var incident = repository.findById(result.incidentId()).orElseThrow();
        assertThat(incident.type()).isEqualTo(IncidentType.TRANSMISSION_EVENTS_INCOMPLETE);
        assertThat(incident.status()).isEqualTo(IncidentStatus.PENDING_HUMAN_REVIEW);
        assertThat(repository.listEvidence(incident.incidentId()))
                .singleElement()
                .satisfies(evidence -> {
                    assertThat(evidence.type()).isEqualTo(EvidenceType.TRANSMISSION_EVENTS_INCOMPLETE);
                    assertThat(evidence.auditFacts()).containsEntry("totalEventCount", "10")
                            .containsEntry("receivedEventCount", "6");
                });
        assertThat(repository.listRuns(incident.incidentId())).isEmpty();
    }

    private IntegrationEventCommand scheduledMiss(
            String eventId,
            String correlationRef,
            Instant expected,
            Instant next
    ) {
        return command(eventId, IntegrationEventKind.ISSUE_DETECTED, correlationRef,
                new IssueDetectedDetails(DetectedIssueType.SCHEDULED_MISSED, expected, next));
    }

    private IntegrationEventCommand issueCommand(
            String eventId,
            String correlationRef,
            DetectedIssueType issueType
    ) {
        Instant expected = issueType == DetectedIssueType.SCHEDULED_MISSED ? EXPECTED_AT : null;
        Instant next = issueType == DetectedIssueType.SCHEDULED_MISSED ? NEXT_AT : null;
        return command(eventId, IntegrationEventKind.ISSUE_DETECTED, correlationRef,
                new IssueDetectedDetails(issueType, expected, next));
    }

    private IntegrationEventCommand command(
            String eventId,
            IntegrationEventKind kind,
            String correlationRef,
            IntegrationEventDetails details
    ) {
        return new IntegrationEventCommand(
                eventId, 2, kind, "trace-001", correlationRef, "device-001",
                "record-" + eventId, Instant.parse("2026-09-04T01:00:00Z"),
                new DeviceDescriptor(DeviceFamily.ICM, "DEMO_ICM_V1", "1.2.0", 120), details);
    }

    private ResolvedInvestigationProfile profile() {
        return new ResolvedInvestigationProfile(
                ProfileResolutionStatus.SUPPORTED, "demo-icm-v1", Set.of(), Set.of("icm"), false,
                "matched by test profile");
    }
}
