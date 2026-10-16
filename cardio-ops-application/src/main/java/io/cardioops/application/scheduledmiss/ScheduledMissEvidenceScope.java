package io.cardioops.application.scheduledmiss;

import java.util.Objects;

/**
 * 这次案件能问 DevicePlatform 的范围。incidentId 只给本地审计。跨系统用 DevicePlatform 自己发出的 correlationRef。模型和调用方不能把范围改到另一台设备。
 */
public record ScheduledMissEvidenceScope(
        String incidentId,
        String correlationRef,
        String deviceRef
) {
    public ScheduledMissEvidenceScope {
        Objects.requireNonNull(incidentId, "incidentId must not be null");
        Objects.requireNonNull(correlationRef, "correlationRef must not be null");
        Objects.requireNonNull(deviceRef, "deviceRef must not be null");
        if (incidentId.isBlank() || correlationRef.isBlank() || deviceRef.isBlank()) {
            throw new IllegalArgumentException("scheduled-miss evidence scope must not be blank");
        }
    }
}
