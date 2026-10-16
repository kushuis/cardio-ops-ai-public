package io.cardioops.domain.incident;

import io.cardioops.domain.device.DeviceDescriptor;
import io.cardioops.domain.device.ProfileResolutionStatus;

import java.time.Instant;
import java.util.Objects;

/**
 * 一件要持续跟进的客服案件。incidentKey 把设备、类型和关联窗口合成业务幂等键，同一窗口不要开两件。
 * 关掉的案件必须有结束时间，还开着的不能提前写结束时间。版本号给乐观锁用，不能是负数。
 *
 *
 * 模型跑完只是调查结束。客服看没看、关没关，是这条生命周期。混在一起，成功调查会被记成失败，或者还没人看就被当成已处理。
 */
public record Incident(
        String incidentId,
        String incidentKey,
        String deviceRef,
        DeviceDescriptor device,
        IncidentType type,
        IncidentSeverity severity,
        IncidentStatus status,
        String correlationRef,
        Instant detectedAt,
        Instant lastEvidenceAt,
        ProfileResolutionStatus profileStatus,
        String profileId,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant resolvedAt
) {
    public Incident {
        Objects.requireNonNull(incidentId, "incidentId must not be null");
        Objects.requireNonNull(incidentKey, "incidentKey must not be null");
        Objects.requireNonNull(deviceRef, "deviceRef must not be null");
        Objects.requireNonNull(device, "device must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(severity, "severity must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(correlationRef, "correlationRef must not be null");
        Objects.requireNonNull(detectedAt, "detectedAt must not be null");
        Objects.requireNonNull(lastEvidenceAt, "lastEvidenceAt must not be null");
        Objects.requireNonNull(profileStatus, "profileStatus must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (incidentId.isBlank() || incidentKey.isBlank() || deviceRef.isBlank() || correlationRef.isBlank()) {
            throw new IllegalArgumentException("incident identity fields must not be blank");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        if (status == IncidentStatus.CLOSED && resolvedAt == null) {
            throw new IllegalArgumentException("CLOSED_INCIDENT_REQUIRES_RESOLVED_AT");
        }
        if (status != IncidentStatus.CLOSED && resolvedAt != null) {
            throw new IllegalArgumentException("ACTIVE_INCIDENT_CANNOT_HAVE_RESOLVED_AT");
        }
    }

    /** 所有入口共用此规则，避免不同适配器生成不一致的案件键。 */
    public static String businessKey(String deviceRef, IncidentType type, String correlationRef) {
        return deviceRef + ":" + type.name() + ":" + correlationRef;
    }

}
