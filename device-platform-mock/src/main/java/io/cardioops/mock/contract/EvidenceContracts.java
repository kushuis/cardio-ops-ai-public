package io.cardioops.mock.contract;

import java.time.Instant;

/** Minimal, de-identified evidence contract shared by the mock MCP tools. */
public final class EvidenceContracts {

    private EvidenceContracts() {
    }

    public enum DeviceFamily { ICM, ICD }
    public enum GatewayConnectivity { UNBOUND, ONLINE, OFFLINE }
    public enum EvidenceUploadChannel { GATEWAY, MINI_PROGRAM }
    public enum ScheduleChangeStatus { PENDING, SUCCEEDED, FAILED, EXPIRED }

    public record DeviceDescriptor(
            DeviceFamily family,
            String modelCode,
            String firmwareVersion,
            long firmwareVersionNumber
    ) { }

    public record ScheduledMissContext(
            String evidenceRef,
            String deviceRef,
            DeviceDescriptor device,
            Instant expectedTransmissionAt,
            Instant nextExpectedTransmissionAt,
            GatewayConnectivity gatewayAtExpected,
            boolean latestBatteryLow,
            Instant batteryObservedAt,
            Instant lastDeviceReportedBroadcastAt,
            Instant deviceBroadcastObservedAt
    ) { }

    public record LowBatteryContext(
            String evidenceRef,
            String deviceRef,
            DeviceDescriptor device,
            Instant observedAt,
            String batteryMessage
    ) { }

    public record TransmissionObservation(
            String evidenceRef,
            EvidenceUploadChannel channel,
            int rawTriggerType,
            Instant startedAt,
            Instant completedAt,
            int totalEventCount,
            int receivedEventCount,
            Instant deviceReportedNextBroadcastAt
    ) { }

    public record GatewaySnapshot(
            String evidenceRef,
            GatewayConnectivity connectivity,
            Instant observedAt,
            Instant lastOnlineAt,
            Instant lastOfflineAt
    ) { }

    public record ScheduleChangeObservation(
            String evidenceRef,
            ScheduleChangeStatus status,
            Instant submittedAt,
            Instant completedAt,
            Instant beforeBroadcastAt,
            Instant targetBroadcastAt
    ) { }
}
