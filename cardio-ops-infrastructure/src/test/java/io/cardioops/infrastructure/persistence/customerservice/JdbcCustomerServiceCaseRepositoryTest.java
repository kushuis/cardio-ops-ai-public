package io.cardioops.infrastructure.persistence.customerservice;

import io.cardioops.application.customerservice.CustomerServiceCaseConflictException;
import io.cardioops.application.customerservice.CustomerServiceCaseQuery;
import io.cardioops.application.customerservice.CustomerServiceCaseService;
import io.cardioops.application.customerservice.DecideClosureProposalCommand;
import io.cardioops.application.customerservice.SubmitCustomerServiceReviewCommand;
import io.cardioops.domain.customerservice.CustomerServiceDecision;
import io.cardioops.domain.customerservice.ClosureProposalStatus;
import io.cardioops.domain.incident.IncidentStatus;
import io.cardioops.domain.lowbattery.LowBatteryImpactAssessment;
import io.cardioops.domain.lowbattery.LowBatteryInvestigationResult;
import io.cardioops.domain.scheduledmiss.ScheduledMissFact;
import io.cardioops.domain.scheduledmiss.ScheduledMissHandlingRecommendation;
import io.cardioops.domain.scheduledmiss.ScheduledMissInvestigationResult;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证客服列表、人审状态机、反馈标签和乐观锁使用的是同一份数据库事实。 */
class JdbcCustomerServiceCaseRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-08T03:00:00Z");
    private JdbcTemplate jdbc;
    private ObjectMapper objectMapper;
    private JdbcCustomerServiceCaseRepository repository;
    private CustomerServiceCaseService service;

    @BeforeEach
    void createDatabase() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:schema-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/integration-intake-schema.sql"),
                new ClassPathResource("db/incident-projection-schema.sql"),
                new ClassPathResource("db/investigation-audit-schema.sql"),
                new ClassPathResource("db/knowledge-index-schema.sql"),
                new ClassPathResource("db/customer-service-schema.sql"),
                new ClassPathResource("db/closure-approval-schema.sql"),
                new ClassPathResource("db/low-battery-investigation-schema.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        objectMapper = new ObjectMapper();
        repository = new JdbcCustomerServiceCaseRepository(
                jdbc, objectMapper, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        service = new CustomerServiceCaseService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
        insertCase(ScheduledMissHandlingRecommendation.NO_CONTACT_NEEDED);
    }

    @Test
    void listsOnlyInvestigatedCustomerServiceCasesAndLoadsDetail() {
        var page = repository.list(new CustomerServiceCaseQuery(
                0, 20, IncidentStatus.PENDING_HUMAN_REVIEW, "device-1"));

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.incidentId()).isEqualTo("incident-1");
            assertThat(item.aiRecommendation()).isEqualTo(ScheduledMissHandlingRecommendation.NO_CONTACT_NEEDED);
            assertThat(item.version()).isEqualTo(1);
        });
        assertThat(repository.findById("incident-1").orElseThrow().reviews()).isEmpty();
        assertThat(repository.findById("incident-1").orElseThrow().investigation()).isNotNull();
    }

    @Test
    void lowBatteryAndIncompleteCasesOpenWithoutAnInvestigation() {
        insertCaseWithoutInvestigation("battery-1", "DEVICE_LOW_BATTERY", "BATTERY:device-1:record-1");
        insertCaseWithoutInvestigation(
                "partial-1", "TRANSMISSION_EVENTS_INCOMPLETE", "TRANSMISSION:device-1:record-2");
        insertCaseWithoutInvestigation(
                "miss-without-result", "SCHEDULED_GATEWAY_TRANSMISSION_MISSED", "MONITORING:device-1:2");

        var page = repository.list(new CustomerServiceCaseQuery(
                0, 20, IncidentStatus.PENDING_HUMAN_REVIEW, "device-1"));

        assertThat(page.items()).extracting(item -> item.incidentId())
                .containsExactlyInAnyOrder("incident-1", "battery-1", "partial-1");
        var battery = repository.findById("battery-1").orElseThrow();
        assertThat(battery.investigation()).isNull();
        assertThat(battery.summary().status()).isEqualTo(IncidentStatus.PENDING_HUMAN_REVIEW);
        assertThat(battery.summary().runId()).isNull();
        assertThat(battery.reviews()).isEmpty();
        var incomplete = repository.findById("partial-1").orElseThrow();
        assertThat(incomplete.investigation()).isNull();
        assertThat(incomplete.summary().aiRecommendation()).isNull();
        assertThat(repository.findById("miss-without-result")).isEmpty();
    }

    @Test
    void noContactRequiresSupervisorApprovalAndDuplicateApprovalIsIdempotent() {
        var pending = service.submitReview(new SubmitCustomerServiceReviewCommand(
                "incident-1", 1, "staff-88", CustomerServiceDecision.NO_CONTACT_CONFIRMED,
                "已核对自动唤醒证据，确认无需联系患者"));

        assertThat(pending.summary().status()).isEqualTo(IncidentStatus.PENDING_CLOSURE_APPROVAL);
        assertThat(pending.summary().version()).isEqualTo(2);
        assertThat(pending.reviews()).singleElement().satisfies(review -> {
            assertThat(review.operatorRef()).isEqualTo("staff-88");
            assertThat(review.acceptedAiRecommendation()).isTrue();
        });
        var proposal = pending.closureProposals().getFirst();
        assertThatThrownBy(() -> service.decideClosureProposal(new DecideClosureProposalCommand(
                "incident-1", proposal.proposalId(), 2, "staff-88", true, "尝试审批自己的提案")))
                .isInstanceOf(CustomerServiceCaseConflictException.class);
        var detail = service.decideClosureProposal(new DecideClosureProposalCommand(
                "incident-1", proposal.proposalId(), 2, "supervisor-1", true,
                "恢复证据仍有效，同意关单"));
        var duplicate = service.decideClosureProposal(new DecideClosureProposalCommand(
                "incident-1", proposal.proposalId(), 2, "supervisor-1", true,
                "重复提交同一决定"));

        assertThat(detail.summary().status()).isEqualTo(IncidentStatus.CLOSED);
        assertThat(detail.summary().version()).isEqualTo(3);
        assertThat(duplicate.summary().version()).isEqualTo(3);
        assertThat(detail.closureProposals()).singleElement().satisfies(saved -> {
            assertThat(saved.status()).isEqualTo(ClosureProposalStatus.APPROVED);
            assertThat(saved.verificationCode()).isEqualTo("RECOVERY_EVIDENCE_VERIFIED");
        });
        assertThat(jdbc.queryForObject(
                "SELECT resolved_at FROM incident WHERE incident_id = 'incident-1'", Timestamp.class))
                .isNotNull();
    }

    @Test
    void changedRecoveryEvidencePreventsClosureAndReturnsCaseToReview() {
        var pending = service.submitReview(new SubmitCustomerServiceReviewCommand(
                "incident-1", 1, "staff-88", CustomerServiceDecision.NO_CONTACT_CONFIRMED,
                "基于当前自动唤醒证据申请关单"));
        var changed = investigationResult(ScheduledMissHandlingRecommendation.NO_CONTACT_NEEDED, "tx-new");
        jdbc.update("UPDATE investigation_result SET result_json = ? WHERE run_id = 'run-1'",
                objectMapper.writeValueAsString(changed));

        var detail = service.decideClosureProposal(new DecideClosureProposalCommand(
                "incident-1", pending.closureProposals().getFirst().proposalId(), 2,
                "supervisor-1", true, "同意，但服务端必须重新核验证据"));

        assertThat(detail.summary().status()).isEqualTo(IncidentStatus.PENDING_HUMAN_REVIEW);
        assertThat(detail.closureProposals()).singleElement().satisfies(saved -> {
            assertThat(saved.status()).isEqualTo(ClosureProposalStatus.VERIFICATION_FAILED);
            assertThat(saved.verificationCode()).isEqualTo("RECOVERY_EVIDENCE_CHANGED");
        });
    }

    @Test
    void contactDecisionKeepsCaseOpenAndRecordsOverriddenNoContactRecommendation() {
        var detail = service.submitReview(new SubmitCustomerServiceReviewCommand(
                "incident-1", 1, "staff-89", CustomerServiceDecision.CONTACT_PATIENT_REQUIRED,
                "结合人工复核结果，需要联系患者确认设备位置"));

        assertThat(detail.summary().status()).isEqualTo(IncidentStatus.FOLLOW_UP_REQUIRED);
        assertThat(detail.reviews()).singleElement()
                .satisfies(review -> assertThat(review.acceptedAiRecommendation()).isFalse());
        assertThat(jdbc.queryForObject(
                "SELECT resolved_at FROM incident WHERE incident_id = 'incident-1'", Timestamp.class))
                .isNull();
    }

    @Test
    void lowBatteryListShowsJavaImpactInsteadOfTheModelSummary() {
        insertLowBatteryResult("battery-result", "run-battery",
                LowBatteryImpactAssessment.NO_OBSERVED_IMPACT, "模型说已经联系患者并关闭");

        var item = repository.list(new CustomerServiceCaseQuery(
                        0, 20, IncidentStatus.PENDING_HUMAN_REVIEW, "device-1"))
                .items().stream()
                .filter(summary -> summary.incidentId().equals("battery-result"))
                .findFirst().orElseThrow();

        assertThat(item.impactAssessment()).isEqualTo(LowBatteryImpactAssessment.NO_OBSERVED_IMPACT);
        assertThat(item.aiSummary()).isEqualTo(LowBatteryImpactAssessment.NO_OBSERVED_IMPACT.listText());
        assertThat(item.aiSummary()).doesNotContain("模型");
        assertThat(item.recovered()).isNull();
        assertThat(item.aiRecommendation()).isNull();
    }

    @Test
    void lowBatteryWithoutInvestigationCanBeReviewedAndClosedByAnotherPerson() {
        insertCaseWithoutInvestigation("battery-1", "DEVICE_LOW_BATTERY", "BATTERY:device-1:record-1");

        var followUp = service.submitReview(new SubmitCustomerServiceReviewCommand(
                "battery-1", 1, "staff-1", CustomerServiceDecision.CONTACT_PATIENT_REQUIRED,
                "ICD 低电量没有自动调查，先人工跟进"));
        assertThat(followUp.summary().status()).isEqualTo(IncidentStatus.FOLLOW_UP_REQUIRED);
        assertThat(followUp.lowBatteryInvestigation()).isNull();
        assertThat(followUp.reviews()).singleElement()
                .satisfies(review -> assertThat(review.runId()).isNull());

        var pending = service.submitReview(new SubmitCustomerServiceReviewCommand(
                "battery-1", 2, "staff-1", CustomerServiceDecision.NO_CONTACT_CONFIRMED,
                "人工确认这台设备暂时不需要再联系"));
        assertThat(pending.summary().status()).isEqualTo(IncidentStatus.PENDING_CLOSURE_APPROVAL);
        var proposal = pending.closureProposals().getFirst();
        var closed = service.decideClosureProposal(new DecideClosureProposalCommand(
                "battery-1", proposal.proposalId(), 3, "supervisor-1", true, "另一人确认关闭"));

        assertThat(closed.summary().status()).isEqualTo(IncidentStatus.CLOSED);
        assertThat(closed.closureProposals()).singleElement().satisfies(saved ->
                assertThat(saved.verificationCode()).isEqualTo("LOW_BATTERY_HUMAN_CONFIRMED"));
    }

    @Test
    void incompleteTransmissionWithoutInvestigationCanBeReviewedAndClosed() {
        insertCaseWithoutInvestigation(
                "partial-1", "TRANSMISSION_EVENTS_INCOMPLETE", "TRANSMISSION:device-1:record-2");

        var pending = service.submitReview(new SubmitCustomerServiceReviewCommand(
                "partial-1", 1, "staff-1", CustomerServiceDecision.NO_CONTACT_CONFIRMED,
                "人工核对事件数量后申请关单"));
        var proposal = pending.closureProposals().getFirst();
        var closed = service.decideClosureProposal(new DecideClosureProposalCommand(
                "partial-1", proposal.proposalId(), 2, "supervisor-1", true, "复核后同意关闭"));

        assertThat(closed.summary().status()).isEqualTo(IncidentStatus.CLOSED);
        assertThat(closed.closureProposals()).singleElement().satisfies(saved ->
                assertThat(saved.verificationCode()).isEqualTo("MANUAL_REVIEW_CONFIRMED"));
    }

    @Test
    void staleVersionCannotOverwriteTheFirstCustomerServiceDecision() {
        service.submitReview(new SubmitCustomerServiceReviewCommand(
                "incident-1", 1, "staff-1", CustomerServiceDecision.CONTINUE_FOLLOW_UP,
                "暂时无法判断，等待补充设备现场信息"));

        assertThatThrownBy(() -> service.submitReview(new SubmitCustomerServiceReviewCommand(
                "incident-1", 1, "staff-2", CustomerServiceDecision.NO_CONTACT_CONFIRMED,
                "使用旧页面版本提交")))
                .isInstanceOf(CustomerServiceCaseConflictException.class);
        assertThat(repository.findById("incident-1").orElseThrow().reviews()).hasSize(1);
    }

    private void insertCaseWithoutInvestigation(String incidentId, String incidentType, String correlationRef) {
        Timestamp created = Timestamp.from(NOW.minusSeconds(120));
        jdbc.update("""
                INSERT INTO incident(
                    incident_id, incident_key, device_ref, device_family, model_code,
                    firmware_version, firmware_version_number, incident_type, severity, status,
                    correlation_ref, detected_at, last_evidence_at, profile_status, profile_id,
                    version, created_at, updated_at, resolved_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, incidentId, "key-" + incidentId, "device-1", "ICM", "DEMO_ICM_V1", "1.2.0", 120L,
                incidentType, "UNASSESSED", "PENDING_HUMAN_REVIEW",
                correlationRef, created, created, "SUPPORTED", "demo-icm-v1",
                1L, created, created, null);
    }

    private void insertCase(ScheduledMissHandlingRecommendation recommendation) {
        Timestamp created = Timestamp.from(NOW.minusSeconds(600));
        Timestamp investigated = Timestamp.from(NOW.minusSeconds(300));
        jdbc.update("""
                INSERT INTO incident(
                    incident_id, incident_key, device_ref, device_family, model_code,
                    firmware_version, firmware_version_number, incident_type, severity, status,
                    correlation_ref, detected_at, last_evidence_at, profile_status, profile_id,
                    version, created_at, updated_at, resolved_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "incident-1", "key-1", "device-1", "ICM", "DEMO_ICM_V1", "1.2.0", 120L,
                "SCHEDULED_GATEWAY_TRANSMISSION_MISSED", "UNASSESSED", "PENDING_HUMAN_REVIEW",
                "MONITORING:device-1:1788656400", created, created, "SUPPORTED", "demo-icm-v1",
                1L, created, investigated, null);
        jdbc.update("""
                INSERT INTO agent_run(
                    run_id, incident_id, trigger_event_id, graph_thread_id, run_status,
                    profile_id, trace_id, active_slot, created_at, updated_at, failure_code
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "run-1", "incident-1", "event-1", "thread-1", "COMPLETED",
                "demo-icm-v1", "trace-1", null, created, investigated, null);
        var result = investigationResult(recommendation, "tx-1");
        jdbc.update("""
                INSERT INTO investigation_result(
                    run_id, incident_id, result_json, recovered, review_recommendation, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, "run-1", "incident-1", objectMapper.writeValueAsString(result), true,
                recommendation.name(), investigated);
    }

    private void insertLowBatteryResult(
            String incidentId,
            String runId,
            LowBatteryImpactAssessment assessment,
            String modelSummary
    ) {
        Timestamp created = Timestamp.from(NOW.minusSeconds(120));
        insertCaseWithoutInvestigation(incidentId, "DEVICE_LOW_BATTERY", "BATTERY:device-1:" + incidentId);
        jdbc.update("""
                INSERT INTO agent_run(
                    run_id, incident_id, trigger_event_id, graph_thread_id, run_status,
                    profile_id, trace_id, active_slot, created_at, updated_at, failure_code
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, runId, incidentId, "event-" + incidentId, "thread-" + incidentId, "COMPLETED",
                "demo-icm-v1", "trace-" + incidentId, null, created, created, null);
        var result = new LowBatteryInvestigationResult(
                runId, incidentId, assessment, true, List.of(), List.of(), List.of(), List.of(),
                List.of("battery:1"), modelSummary, NOW.minusSeconds(30));
        jdbc.update("""
                INSERT INTO low_battery_investigation_result(
                    run_id, incident_id, result_json, impact_assessment, completed_at
                ) VALUES (?, ?, ?, ?, ?)
                """, runId, incidentId, objectMapper.writeValueAsString(result), assessment.name(),
                Timestamp.from(NOW.minusSeconds(30)));
    }

    private ScheduledMissInvestigationResult investigationResult(
            ScheduledMissHandlingRecommendation recommendation,
            String evidenceRef
    ) {
        return new ScheduledMissInvestigationResult(
                "run-1", "incident-1", true, true, true, false, false, recommendation,
                List.of(evidenceRef), List.of(new ScheduledMissFact(evidenceRef, "发现自动唤醒上传")),
                List.of(), List.of(), List.of(), "已发现能够证明自动唤醒的传输",
                NOW.minusSeconds(300));
    }
}
