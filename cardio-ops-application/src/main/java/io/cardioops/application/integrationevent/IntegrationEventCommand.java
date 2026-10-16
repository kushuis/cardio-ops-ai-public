package io.cardioops.application.integrationevent;

import io.cardioops.domain.device.DeviceDescriptor;

import java.time.Instant;
import java.util.Objects;

/**
 * 解码并且校验过、准备进 Inbox 的命令。eventId 是第一道幂等。业务键是第二道。两道都过了才谈投影。
 */
public record IntegrationEventCommand(
        String eventId,
        int schemaVersion,
        IntegrationEventKind eventType,
        String traceId,
        String correlationRef,
        String deviceRef,
        String sourceRecordRef,
        Instant occurredAt,
        DeviceDescriptor device,
        IntegrationEventDetails details
) {
    public IntegrationEventCommand {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
        Objects.requireNonNull(deviceRef, "deviceRef must not be null");
        Objects.requireNonNull(sourceRecordRef, "sourceRecordRef must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(device, "device must not be null");
        Objects.requireNonNull(details, "details must not be null");
        if (schemaVersion < 1 || schemaVersion > 2) {
            throw new IllegalArgumentException("unsupported schemaVersion: " + schemaVersion);
        }
        if (schemaVersion == 2 && (correlationRef == null || correlationRef.isBlank())) {
            throw new IllegalArgumentException("schemaVersion 2 requires correlationRef");
        }
        boolean correctDetailsType = details instanceof IssueDetectedDetails;
        if (!correctDetailsType) {
            throw new IllegalArgumentException("eventType and details type do not match");
        }
    }

    /**
     * 第二层业务幂等键：即使上游为同一事实误生成了不同 eventId，也不会重复建立入口。
     * sourceRecordRef 必须是传输会话、任务、检测记录等稳定 ID，不能每次重试都重新生成。
     */
    public String businessKey() {
        return eventType.name() + ":" + deviceRef + ":" + sourceRecordRef;
    }
}
