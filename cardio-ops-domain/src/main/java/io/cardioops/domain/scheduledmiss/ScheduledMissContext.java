package io.cardioops.domain.scheduledmiss;

import io.cardioops.domain.device.DeviceDescriptor;

import java.time.Instant;
import java.util.Objects;

/**
 * 应传未传调查的初始事实。下一次应传必须晚于这一次。低电量如果被引用，必须带观测时间。
 * 设备上报的广播时间和观测时间要成对出现，不能只剩一半。
 */
public record ScheduledMissContext(
        String evidenceRef,
        String incidentId,
        String deviceRef,
        DeviceDescriptor device,
        Instant expectedTransmissionAt,
        Instant nextExpectedTransmissionAt,
        GatewayConnectivity gatewayAtExpected,
        boolean latestBatteryLow,
        Instant batteryObservedAt,
        Instant lastDeviceReportedBroadcastAt,
        Instant deviceBroadcastObservedAt
) {
    public ScheduledMissContext {
        Objects.requireNonNull(evidenceRef, "evidenceRef must not be null");
        Objects.requireNonNull(incidentId, "incidentId must not be null");
        Objects.requireNonNull(deviceRef, "deviceRef must not be null");
        Objects.requireNonNull(device, "device must not be null");
        Objects.requireNonNull(expectedTransmissionAt, "expectedTransmissionAt must not be null");
        Objects.requireNonNull(nextExpectedTransmissionAt, "nextExpectedTransmissionAt must not be null");
        Objects.requireNonNull(gatewayAtExpected, "gatewayAtExpected must not be null");
        if (!nextExpectedTransmissionAt.isAfter(expectedTransmissionAt)) {
            throw new IllegalArgumentException("next expected time must be after current expected time");
        }
        if (latestBatteryLow && batteryObservedAt == null) {
            throw new IllegalArgumentException("low battery evidence must have observedAt");
        }
        if ((lastDeviceReportedBroadcastAt == null) != (deviceBroadcastObservedAt == null)) {
            throw new IllegalArgumentException("device broadcast value and observedAt must appear together");
        }
    }
}
