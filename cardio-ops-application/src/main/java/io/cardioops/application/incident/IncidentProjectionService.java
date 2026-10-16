package io.cardioops.application.incident;

import io.cardioops.application.integrationevent.IntegrationEventCommand;
import io.cardioops.application.integrationevent.IntegrationEventDetails;
import io.cardioops.application.integrationevent.IssueDetectedDetails;
import io.cardioops.application.scheduledmiss.ScheduledMissRecheckStore;
import io.cardioops.domain.agent.AgentRun;
import io.cardioops.domain.agent.AgentRunStatus;
import io.cardioops.domain.incident.EvidenceType;
import io.cardioops.domain.incident.Incident;
import io.cardioops.domain.incident.IncidentEvidence;
import io.cardioops.domain.incident.IncidentSeverity;
import io.cardioops.domain.incident.IncidentStatus;
import io.cardioops.domain.incident.IncidentType;
import io.cardioops.domain.device.ResolvedInvestigationProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * 把已经确认的三类问题收成案件或证据：应传未传、低电量、传输事件不全。
 * Topic 只进异常事实，正常传输由调查 Agent 通过 DevicePlatform MCP 按需查询。
 * 每个异常关联窗口独立成案；相同窗口依靠 incident_key 幂等复用。
 * 低电量和事件不全当前不强行运行没有 Handler 的 Agent。
 * 优先级业务没定，这里一律未评估，不把低电量或部分成功自己标成高。
 *
 *
 * 成功传输量大，而且只有调查时才需要。异常触发、MCP 拉证据，能避免复制一份最终仍需回源确认的数据。
 */
public final class IncidentProjectionService implements IncidentProjectionUseCase {

    private static final Logger log = LoggerFactory.getLogger(IncidentProjectionService.class);

    private final IncidentStore store;
    private final ScheduledMissRecheckStore recheckStore;
    private final Clock clock;
    private final Duration recheckGrace;

    public IncidentProjectionService(
            IncidentStore store,
            ScheduledMissRecheckStore recheckStore,
            Clock clock,
            Duration recheckGrace
    ) {
        this.store = Objects.requireNonNull(store);
        this.recheckStore = Objects.requireNonNull(recheckStore);
        this.clock = Objects.requireNonNull(clock);
        this.recheckGrace = Objects.requireNonNull(recheckGrace);
        if (recheckGrace.isNegative()) {
            throw new IllegalArgumentException("recheckGrace must not be negative");
        }
    }

    @Override
    /**
     * 先校验关联键，再把三类异常映射为案件。这里只判断顶层问题，不推断根因。
     */
    public IncidentProjectionResult project(
            IntegrationEventCommand command,
            ResolvedInvestigationProfile profile
    ) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(profile);
        if (command.correlationRef() == null || command.correlationRef().isBlank()) {
            log.warn("incident projection skipped reason=missing_correlation eventId={} traceId={} eventType={} deviceRef={}",
                    command.eventId(), command.traceId(), command.eventType(), command.deviceRef());
            return new IncidentProjectionResult(
                    IncidentProjectionStatus.UNCORRELATED_EVENT, null, null, false);
        }

        Classification classification = classify(command.details());
        return createOrReuseIncident(command, profile, classification);
    }

    private Classification classify(IntegrationEventDetails details) {
        return switch (details) {
            case IssueDetectedDetails issue -> switch (issue.issueType()) {
                case SCHEDULED_MISSED -> new Classification(
                        IncidentType.SCHEDULED_GATEWAY_TRANSMISSION_MISSED,
                        EvidenceType.SCHEDULED_MISSED);
                case LOW_BATTERY -> new Classification(
                        IncidentType.DEVICE_LOW_BATTERY,
                        EvidenceType.LOW_BATTERY);
                case TRANSMISSION_EVENTS_INCOMPLETE -> new Classification(
                        IncidentType.TRANSMISSION_EVENTS_INCOMPLETE,
                        EvidenceType.TRANSMISSION_EVENTS_INCOMPLETE);
            };
        };
    }

    private IncidentProjectionResult createOrReuseIncident(
            IntegrationEventCommand command,
            ResolvedInvestigationProfile profile,
            Classification classification
    ) {
        var now = clock.instant();
        IssueDetectedDetails details = (IssueDetectedDetails) command.details();
        boolean scheduledMiss = classification.incidentType()
                == IncidentType.SCHEDULED_GATEWAY_TRANSMISSION_MISSED;
        boolean lowBatteryAgentSupported = classification.incidentType() == IncidentType.DEVICE_LOW_BATTERY
                && command.device().family() == io.cardioops.domain.device.DeviceFamily.ICM;
        boolean agentSupported = scheduledMiss || lowBatteryAgentSupported;
        String incidentId = UUID.randomUUID().toString();
        // 应传未传进入自动调查/复查；ICM 低电量进入一次只读调查；事件不全直接交给客服处理。
        IncidentStatus initialStatus = agentSupported
                ? IncidentStatus.DETECTED
                : IncidentStatus.PENDING_HUMAN_REVIEW;
        var candidate = new Incident(
                incidentId,
                Incident.businessKey(command.deviceRef(), classification.incidentType(), command.correlationRef()),
                command.deviceRef(), command.device(), classification.incidentType(),
                // Severity remains conservative until the business policy defines an explicit escalation rule.
                IncidentSeverity.UNASSESSED,
                initialStatus,
                command.correlationRef(), command.occurredAt(), command.occurredAt(),
                profile.status(), profile.profileId(), 0, now, now, null);
        // incident_key 包含 correlationRef：同一窗口重复投递复用旧案，
        // 不同应传窗口即使设备相同，也各自建案、调查和复查。
        IncidentUpsertResult upsert = store.createOrFind(candidate);
        Incident incident = upsert.incident();

        var evidence = evidence(command, classification.evidenceType(), incident.incidentId());
        boolean evidenceInserted = store.saveEvidenceIfAbsent(evidence);
        if (evidenceInserted) {
            store.advanceLastEvidenceAt(incident.incidentId(), command.occurredAt());
        }
        boolean runQueued = false;
        if (scheduledMiss) {
            Duration interval = Duration.between(
                    details.expectedTransmissionAt(), details.nextTransmissionAt());
            recheckStore.initializeOrRefresh(
                    incident.incidentId(), interval,
                    details.nextTransmissionAt().plus(recheckGrace),
                    upsert.created(), now);
        }
        // 应传未传和 ICM 低电量都有独立 Handler；事件不全以及暂未核对的 ICD 低电量直接交给客服。
        if (upsert.created() && agentSupported) {
            var run = new AgentRun(
                    UUID.randomUUID().toString(), incident.incidentId(), command.eventId(),
                    "incident:" + incident.incidentId() + ":initial", AgentRunStatus.QUEUED,
                    profile.profileId(), command.traceId(), 0, null, null, null, now, now);
            runQueued = store.queueRunIfNoActive(run);
        }

        log.info("incident projection completed eventId={} traceId={} deviceRef={} correlationRef={} "
                        + "incidentId={} incidentType={} created={} evidenceInserted={} runQueued={} recheckEnabled={}",
                command.eventId(), command.traceId(), command.deviceRef(), command.correlationRef(),
                incident.incidentId(), incident.type(), upsert.created(), evidenceInserted, runQueued, scheduledMiss);
        return new IncidentProjectionResult(
                upsert.created()
                        ? IncidentProjectionStatus.INCIDENT_CREATED
                        : IncidentProjectionStatus.INCIDENT_ALREADY_EXISTS,
                incident.incidentId(), evidenceInserted ? evidence.evidenceId() : null, runQueued);
    }

    private IncidentEvidence evidence(
            IntegrationEventCommand command,
            EvidenceType type,
            String incidentId
    ) {
        return new IncidentEvidence(
                UUID.randomUUID().toString(), command.eventId() + ":" + type.name(), incidentId,
                command.eventId(), type, command.deviceRef(), command.sourceRecordRef(),
                command.correlationRef(), command.occurredAt(), command.details().auditFacts(), clock.instant());
    }

    /** 一次投影所需的最小分类结果，不提前加入严重度、根因等未确认规则。 */
    private record Classification(IncidentType incidentType, EvidenceType evidenceType) {
    }
}
