package io.cardioops.application.customerservice;

import io.cardioops.domain.device.DeviceFamily;
import io.cardioops.domain.incident.IncidentStatus;
import io.cardioops.domain.incident.IncidentType;
import io.cardioops.domain.lowbattery.LowBatteryImpactAssessment;
import io.cardioops.domain.scheduledmiss.ScheduledMissHandlingRecommendation;

import java.time.Instant;
import java.util.Objects;

/**
 * 列表里的一行。不把整份工具证据和模型上下文塞进列表接口。
 * recovered 只对应传未传的 Java 恢复位。低电量没有这一位，列表改看 impactAssessment。
 * 事件不全没有 Agent 调查；低电量在调查完成前，影响口径也可能为空。
 */
public record CustomerServiceCaseSummary(
        String incidentId,
        String deviceRef,
        DeviceFamily deviceFamily,
        String modelCode,
        String firmwareVersion,
        IncidentStatus status,
        long version,
        Instant detectedAt,
        Instant updatedAt,
        String runId,
        Boolean recovered,
        ScheduledMissHandlingRecommendation aiRecommendation,
        String aiSummary,
        Instant investigatedAt,
        IncidentType incidentType,
        LowBatteryImpactAssessment impactAssessment
) {
    public CustomerServiceCaseSummary {
        Objects.requireNonNull(incidentId, "incidentId must not be null");
        Objects.requireNonNull(deviceRef, "deviceRef must not be null");
        Objects.requireNonNull(deviceFamily, "deviceFamily must not be null");
        Objects.requireNonNull(modelCode, "modelCode must not be null");
        Objects.requireNonNull(firmwareVersion, "firmwareVersion must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(detectedAt, "detectedAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        Objects.requireNonNull(incidentType, "incidentType must not be null");
    }
}
