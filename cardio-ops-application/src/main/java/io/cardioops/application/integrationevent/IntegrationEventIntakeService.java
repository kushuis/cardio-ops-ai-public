package io.cardioops.application.integrationevent;

import io.cardioops.application.incident.IncidentProjectionResult;
import io.cardioops.application.incident.IncidentProjectionStatus;
import io.cardioops.application.incident.IncidentProjectionUseCase;
import io.cardioops.domain.device.InvestigationProfileResolver;
import io.cardioops.domain.device.ProfileResolutionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * 事件进门之后的用例。Outbox 不在这里，Outbox 在 DevicePlatform。
 * Inbox 对事件信封去重，业务键对同一业务事实去重，形成双重幂等保护。
 *
 * <p>eventId 相同，是 Kafka 把同一个信封又投了一次。第一道门直接跳过，Profile 都不再解析。
 * eventId 不同、businessKey 相同，是两封信在说同一件事实。第二道门挡掉，不再投影案件。
 * 两道写入和投影包在同一个本地事务里，事务本身由外面的 infrastructure 套上。
 * 投影失败就整单回滚，Inbox 注册也撤掉。Kafka 再投时才是真的重试，不会被第一道门当成重复信封丢掉。</p>
 *
 * <p>Profile 按事件发生时的设备版本选，不用“现在”去套后来才发布的档案。
 * 不支持或冲突的档案状态也会进入投影，确保此类事件仍可形成案件并转人工处理。</p>
 *
 * <p>
 * eventId 防的是同一条消息重投。业务键防的是上游换了信封、说的还是同一件事。只挡信封，同一件事实会进两次。</p>
 */
public final class IntegrationEventIntakeService implements IntegrationEventIntakeUseCase {

    private static final Logger log = LoggerFactory.getLogger(IntegrationEventIntakeService.class);

    private final IntegrationInboxPort inbox;
    private final IntegrationIntakePort intakeStore;
    private final InvestigationProfileResolver profileResolver;
    private final IncidentProjectionUseCase incidentProjection;
    private final Clock clock;

    public IntegrationEventIntakeService(
            IntegrationInboxPort inbox,
            IntegrationIntakePort intakeStore,
            InvestigationProfileResolver profileResolver,
            Clock clock
    ) {
        // 测试可以不带真正的投影器。生产走下面那个构造，投影和 Inbox 在同一个事务里。
        this(inbox, intakeStore, profileResolver,
                (command, profile) -> new IncidentProjectionResult(
                        IncidentProjectionStatus.UNCORRELATED_EVENT, null, null, false),
                clock);
    }

    public IntegrationEventIntakeService(
            IntegrationInboxPort inbox,
            IntegrationIntakePort intakeStore,
            InvestigationProfileResolver profileResolver,
            IncidentProjectionUseCase incidentProjection,
            Clock clock
    ) {
        this.inbox = Objects.requireNonNull(inbox);
        this.intakeStore = Objects.requireNonNull(intakeStore);
        this.profileResolver = Objects.requireNonNull(profileResolver);
        this.incidentProjection = Objects.requireNonNull(incidentProjection);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 先占信封，再解析档案，再按业务键决定要不要投影。
     * 重复信封在解析之前就返回。重复事实仍然把 Inbox 标成处理过，只是不再投影。
     * 这一步中途抛异常，外面的事务要把 Inbox 一起撤掉，否则重试会被第一道门挡死。
     *
     * <p>
     * 信封是新的，不标记的话这个 eventId 会一直被重试。事实已经进过了，把信封记成处理完，重试才会停。</p>
     */
    @Override
    public IntegrationIntakeResult consume(IntegrationEventCommand command) {
        if (!inbox.tryRegister(command)) {
            log.info(
                    "integration intake skipped reason=duplicate_event eventId={} traceId={} eventType={} deviceRef={}",
                    command.eventId(), command.traceId(), command.eventType(), command.deviceRef());
            return new IntegrationIntakeResult(
                    command.eventId(), IntegrationIntakeStatus.DUPLICATE_EVENT, null, null, null);
        }

        // 用事件发生时间选档案。用现在的时间，会把后来才发布的 Profile 套到旧事件上。
        var profile = profileResolver.resolve(command.device(), command.occurredAt());
        if (profile.status() == ProfileResolutionStatus.SUPPORTED) {
            log.debug(
                    "device profile resolved eventId={} traceId={} deviceRef={} family={} modelCode={} "
                            + "firmwareVersionNumber={} profileId={}",
                    command.eventId(), command.traceId(), command.deviceRef(), command.device().family(),
                    command.device().modelCode(), command.device().firmwareVersionNumber(), profile.profileId());
        } else {
            // 降级会收窄后面能用的工具，所以留一条不含患者数据的告警。事件本身仍然继续进门。
            log.warn(
                    "device profile degraded eventId={} traceId={} deviceRef={} family={} modelCode={} "
                            + "firmwareVersionNumber={} profileStatus={} reason={}",
                    command.eventId(), command.traceId(), command.deviceRef(), command.device().family(),
                    command.device().modelCode(), command.device().firmwareVersionNumber(),
                    profile.status(), profile.reason());
        }
        String intakeId = UUID.randomUUID().toString();
        var intake = new PendingIntegrationIntake(
                intakeId,
                command.businessKey(),
                command.correlationRef(),
                command.eventId(),
                command.eventType(),
                command.deviceRef(),
                command.device(),
                profile.status(),
                profile.profileId(),
                command.occurredAt(),
                clock.instant(),
                command.details().auditFacts()
        );
        boolean created = intakeStore.createIfAbsent(intake);
        var status = created
                ? IntegrationIntakeStatus.ACCEPTED
                : IntegrationIntakeStatus.DUPLICATE_BUSINESS_FACT;
        if (created) {
            IncidentProjectionResult projection = incidentProjection.project(command, profile);
            log.info("integration incident projected eventId={} traceId={} deviceRef={} correlationRef={} "
                            + "projectionStatus={} incidentId={} agentRunQueued={}",
                    command.eventId(), command.traceId(), command.deviceRef(), command.correlationRef(),
                    projection.status(), projection.incidentId(), projection.agentRunQueued());
        }
        inbox.markProcessed(command.eventId(), status.name());

        log.info(
                "integration intake completed eventId={} traceId={} eventType={} deviceRef={} businessKey={} "
                        + "status={} intakeId={} profileStatus={} profileId={}",
                command.eventId(), command.traceId(), command.eventType(), command.deviceRef(), command.businessKey(),
                status, created ? intakeId : null, profile.status(), profile.profileId());

        return new IntegrationIntakeResult(
                command.eventId(), status, created ? intakeId : null, profile.status(), profile.profileId());
    }
}
