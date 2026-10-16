package io.cardioops.agent.scheduledmiss;

import io.cardioops.domain.agent.AgentRun;
import io.cardioops.domain.knowledge.KnowledgeSnippet;
import io.cardioops.domain.agent.AgentRunStatus;
import io.cardioops.domain.device.DeviceDescriptor;
import io.cardioops.domain.device.DeviceFamily;
import io.cardioops.domain.scheduledmiss.EvidenceUploadChannel;
import io.cardioops.domain.scheduledmiss.GatewayConnectivity;
import io.cardioops.domain.scheduledmiss.ScheduledMissHypothesisCode;
import io.cardioops.domain.scheduledmiss.IcmTransmissionTrigger;
import io.cardioops.domain.incident.Incident;
import io.cardioops.domain.incident.IncidentSeverity;
import io.cardioops.domain.incident.IncidentStatus;
import io.cardioops.domain.incident.IncidentType;
import io.cardioops.domain.scheduledmiss.ScheduledMissHypothesis;
import io.cardioops.domain.device.ProfileResolutionStatus;
import io.cardioops.domain.scheduledmiss.ScheduledMissHandlingRecommendation;
import io.cardioops.domain.scheduledmiss.ScheduleChangeObservation;
import io.cardioops.domain.scheduledmiss.ScheduleChangeStatus;
import io.cardioops.domain.scheduledmiss.ScheduledMissContext;
import io.cardioops.domain.scheduledmiss.TransmissionObservation;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Java 硬规则测试：模型只能解释证据，不能自己决定恢复和患者联系建议。 */
class ScheduledMissResultValidatorTest {

    private static final Instant EXPECTED_AT = Instant.parse("2026-09-06T01:00:00Z");
    private static final Instant NEXT_AT = Instant.parse("2026-09-07T01:00:00Z");
    private static final Instant NOW = Instant.parse("2026-09-06T03:00:00Z");
    private final ScheduledMissResultValidator validator = new ScheduledMissResultValidator(
            Duration.ofMinutes(3), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void scheduledGatewayUploadWithinThreeMinutesIsRecoveredAndNeedsNoContact() {
        var transmission = transmission("tx-scheduled", EvidenceUploadChannel.GATEWAY,
                IcmTransmissionTrigger.SCHEDULED, EXPECTED_AT.plusSeconds(150));

        var result = validate(List.of(transmission), List.of(), draft());

        assertThat(result.automaticWakeupObserved()).isTrue();
        assertThat(result.recovered()).isTrue();
        assertThat(result.withinGracePeriod()).isTrue();
        assertThat(result.recommendation()).isEqualTo(ScheduledMissHandlingRecommendation.NO_CONTACT_NEEDED);
    }

    @Test
    void eventOrAlertDoesNotReplaceTheNextScheduledTransmissionWhenGatewayWasOnline() {
        var event = transmission("tx-event", EvidenceUploadChannel.GATEWAY,
                IcmTransmissionTrigger.EVENT, EXPECTED_AT.plusSeconds(240));
        var alert = transmission("tx-alert", EvidenceUploadChannel.GATEWAY,
                IcmTransmissionTrigger.ALERT, EXPECTED_AT.plusSeconds(300));

        var result = validate(List.of(event, alert), List.of(), draft());

        assertThat(result.automaticWakeupObserved()).isTrue();
        assertThat(result.recoveryTransmissionObserved()).isFalse();
        assertThat(result.recovered()).isFalse();
        assertThat(result.withinGracePeriod()).isFalse();
        assertThat(result.recommendation()).isEqualTo(ScheduledMissHandlingRecommendation.HUMAN_REVIEW);
    }

    @Test
    void nextScheduledTransmissionRecoversAnOnlineGatewayMiss() {
        var nextScheduled = transmission("tx-next-scheduled", EvidenceUploadChannel.GATEWAY,
                IcmTransmissionTrigger.SCHEDULED, NEXT_AT.plusSeconds(90));
        var recheckValidator = new ScheduledMissResultValidator(
                Duration.ofMinutes(3), Clock.fixed(NEXT_AT.plusSeconds(180), ZoneOffset.UTC));
        var evidence = new CollectedScheduledMissEvidence(
                context(), List.of(nextScheduled), true, null, true, List.of(), true, List.of());

        var result = recheckValidator.validate(
                run(), incident(), new ScheduledMissAgentOutput(draft(), evidence));

        assertThat(result.recoveryTransmissionObserved()).isTrue();
        assertThat(result.recovered()).isTrue();
        assertThat(result.withinGracePeriod()).isTrue();
    }

    @Test
    void anyLaterGatewayTransmissionRecoversAnOfflineGatewayBlock() {
        var magnetGateway = transmission("tx-magnet", EvidenceUploadChannel.GATEWAY,
                IcmTransmissionTrigger.MAGNET, EXPECTED_AT.plusSeconds(600));
        var context = new ScheduledMissContext("context-1", "incident-1", "device-1", device(),
                EXPECTED_AT, NEXT_AT, GatewayConnectivity.OFFLINE, false, null, null, null);
        var evidence = new CollectedScheduledMissEvidence(
                context, List.of(magnetGateway), true, null, true, List.of(), true, List.of());

        var result = validator.validate(run(), incident(), new ScheduledMissAgentOutput(draft(), evidence));

        assertThat(result.automaticWakeupObserved()).isFalse();
        assertThat(result.recoveryTransmissionObserved()).isTrue();
        assertThat(result.recovered()).isTrue();
    }

    @Test
    void manualOnlyUploadProvesLinkButStillRequiresHumanReview() {
        var miniProgram = transmission("tx-mini", EvidenceUploadChannel.MINI_PROGRAM,
                IcmTransmissionTrigger.MAGNET, EXPECTED_AT.plusSeconds(80));
        var magnetGateway = transmission("tx-magnet", EvidenceUploadChannel.GATEWAY,
                IcmTransmissionTrigger.MAGNET, EXPECTED_AT.plusSeconds(120));

        var result = validate(List.of(miniProgram, magnetGateway), List.of(), draft());

        assertThat(result.automaticWakeupObserved()).isFalse();
        assertThat(result.recovered()).isFalse();
        assertThat(result.recommendation()).isEqualTo(ScheduledMissHandlingRecommendation.HUMAN_REVIEW);
        assertThat(result.facts()).extracting(fact -> fact.statement())
                .allMatch(text -> text.contains("手动传输") || text.contains("应传时刻"));
        assertThat(result.missingEvidence()).contains("应传时刻设备是否真正完成自动唤醒（当前系统没有直接遥测）");
    }

    @Test
    void successfulWebScheduleChangeBeforeOldExpectedTimeInvalidatesOldWindow() {
        var change = scheduleChange(ScheduleChangeStatus.SUCCEEDED,
                EXPECTED_AT.minusSeconds(60), NEXT_AT.plusSeconds(3600));

        var result = validate(List.of(), List.of(change), draft());

        assertThat(result.scheduleChangedBeforeExpected()).isTrue();
        assertThat(result.recovered()).isTrue();
        assertThat(result.recommendation()).isEqualTo(ScheduledMissHandlingRecommendation.NO_CONTACT_NEEDED);
    }

    @Test
    void pendingOrLateScheduleChangeCannotInvalidateOldWindow() {
        var pending = scheduleChange(ScheduleChangeStatus.PENDING, null, NEXT_AT.plusSeconds(3600));
        var late = scheduleChange(ScheduleChangeStatus.SUCCEEDED,
                EXPECTED_AT.plusSeconds(1), NEXT_AT.plusSeconds(7200));

        var result = validate(List.of(), List.of(pending, late), draft());

        assertThat(result.scheduleChangedBeforeExpected()).isFalse();
        assertThat(result.recovered()).isFalse();
        assertThat(result.recommendation()).isEqualTo(ScheduledMissHandlingRecommendation.HUMAN_REVIEW);
    }

    @Test
    void rejectsEvidenceReferenceInventedByTheModel() {
        var hypothesis = new ScheduledMissHypothesis(
                ScheduledMissHypothesisCode.DEVICE_NOT_REACHED_OR_NOT_WOKEN,
                List.of("invented-ref"), List.of(), List.of(), "仍需复核");

        assertThatThrownBy(() -> validate(List.of(), List.of(),
                new ScheduledMissModelDraft(List.of(hypothesis), List.of(), List.of(), "等待人工复核")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MODEL_CITED_UNKNOWN_EVIDENCE");
    }

    @Test
    void keepsOnlyKnowledgeCitationActuallyReturnedByRagTool() {
        var snippet = new KnowledgeSnippet(
                "knowledge:playbook:1.0:manual", "playbook", "1.0", "playbook@1.0.0",
                "CardioOps 审核知识库", "ICM 应传未传复核", "PUBLISHED", "demo-icm-v2",
                "排查手册", "manual", "手动上传不能证明自动唤醒恢复。", 0.92,
                Instant.parse("2026-01-01T00:00:00Z"), null, java.util.Set.of("icm"));
        var evidence = new CollectedScheduledMissEvidence(
                context(), List.of(), true, null, true, List.of(), true, List.of(snippet));
        var draft = new ScheduledMissModelDraft(
                List.of(), List.of(snippet.knowledgeRef()), List.of(), "参考当前有效排查手册");

        var result = validator.validate(run(), incident(), new ScheduledMissAgentOutput(draft, evidence));

        assertThat(result.knowledgeCitations()).singleElement().satisfies(citation -> {
            assertThat(citation.knowledgeRef()).isEqualTo(snippet.knowledgeRef());
            assertThat(citation.version()).isEqualTo("1.0");
        });
    }

    @Test
    void rejectsKnowledgeReferenceInventedByTheModel() {
        var evidence = new CollectedScheduledMissEvidence(
                context(), List.of(), true, null, true, List.of(), true, List.of());
        var draft = new ScheduledMissModelDraft(
                List.of(), List.of("knowledge:invented"), List.of(), "引用不存在的知识");

        assertThatThrownBy(() -> validator.validate(
                run(), incident(), new ScheduledMissAgentOutput(draft, evidence)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MODEL_CITED_UNKNOWN_KNOWLEDGE");
    }

    @Test
    void rejectsARecheckThatDidNotQueryAllRequiredEvidenceSources() {
        var evidence = new CollectedScheduledMissEvidence(
                context(), List.of(), true, null, true, List.of(), false, List.of());

        assertThatThrownBy(() -> validator.validate(
                run(), incident(), new ScheduledMissAgentOutput(draft(), evidence)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("REQUIRED_SCHEDULE_CHANGES_NOT_QUERIED");
    }

    private io.cardioops.domain.scheduledmiss.ScheduledMissInvestigationResult validate(
            List<TransmissionObservation> transmissions,
            List<ScheduleChangeObservation> changes,
            ScheduledMissModelDraft draft
    ) {
        var evidence = new CollectedScheduledMissEvidence(
                context(), transmissions, true, null, true, changes, true, List.of());
        return validator.validate(run(), incident(), new ScheduledMissAgentOutput(draft, evidence));
    }

    private ScheduledMissModelDraft draft() {
        return new ScheduledMissModelDraft(List.of(), List.of(), List.of(), "已按规则完成只读调查");
    }

    private TransmissionObservation transmission(
            String ref,
            EvidenceUploadChannel channel,
            IcmTransmissionTrigger trigger,
            Instant completedAt
    ) {
        return new TransmissionObservation(ref, channel, trigger, completedAt.minusSeconds(30), completedAt,
                3, 3, NEXT_AT);
    }

    private ScheduleChangeObservation scheduleChange(
            ScheduleChangeStatus status,
            Instant completedAt,
            Instant targetAt
    ) {
        return new ScheduleChangeObservation("schedule-1", status, EXPECTED_AT.minusSeconds(120),
                completedAt, EXPECTED_AT, targetAt);
    }

    private ScheduledMissContext context() {
        return new ScheduledMissContext("context-1", "incident-1", "device-1", device(),
                EXPECTED_AT, NEXT_AT, GatewayConnectivity.ONLINE, false, null, null, null);
    }

    private AgentRun run() {
        return new AgentRun("run-1", "incident-1", "event-1", "thread-1",
                AgentRunStatus.RUNNING, "demo-icm-v1", "trace-1", 1,
                "worker-1", "lease-1", NOW.plusSeconds(60), NOW, NOW);
    }

    private Incident incident() {
        return new Incident("incident-1", "key-1", "device-1", device(),
                IncidentType.SCHEDULED_GATEWAY_TRANSMISSION_MISSED,
                IncidentSeverity.UNASSESSED, IncidentStatus.DETECTED, "monitoring-1",
                EXPECTED_AT, EXPECTED_AT, ProfileResolutionStatus.SUPPORTED, "demo-icm-v1",
                0, EXPECTED_AT, EXPECTED_AT, null);
    }

    private DeviceDescriptor device() {
        return new DeviceDescriptor(DeviceFamily.ICM, "DEMO_ICM_V1", "1.2.0", 120);
    }
}
