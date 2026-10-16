package io.cardioops.application.integrationevent;

import io.cardioops.domain.device.DeviceDescriptor;
import io.cardioops.domain.device.ProfileResolutionStatus;

import java.time.Instant;
import java.util.Map;

/**
 * 尚未投影为案件的入口记录。intakeId 由本服务生成；跨系统和业务幂等分别由 eventId 与 businessKey 保证。
 */
public record PendingIntegrationIntake(
        String intakeId,
        String businessKey,
        String correlationRef,
        String firstEventId,
        IntegrationEventKind eventType,
        String deviceRef,
        DeviceDescriptor device,
        ProfileResolutionStatus profileStatus,
        String profileId,
        Instant occurredAt,
        Instant createdAt,
        Map<String, String> auditFacts
) {
    public PendingIntegrationIntake {
        auditFacts = Map.copyOf(auditFacts);
    }
}
