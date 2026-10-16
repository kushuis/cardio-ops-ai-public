package io.cardioops.contracts;

import java.time.Instant;
import java.util.Objects;

/**
 * Device Platform 已按领域规则确认的问题，CardioOps 不重复计算上游判定规则。
 * 设备引用是脱敏的。产生问题的源记录引用要稳定，业务键靠它。
 */
public record IssueDetectedPayload(
        String deviceRef,
        String sourceRecordRef,
        DeviceIdentityPayload device,
        PatientServiceIssueType issueType,
        Instant expectedTransmissionAt,
        Instant nextTransmissionAt,
        Integer totalEventCount,
        Integer receivedEventCount
) implements RemoteMonitoringEventPayload {
    public IssueDetectedPayload {
        deviceRef = ContractValidation.requireReference(deviceRef, "deviceRef");
        sourceRecordRef = ContractValidation.requireReference(sourceRecordRef, "sourceRecordRef");
        Objects.requireNonNull(device, "device must not be null");
        Objects.requireNonNull(issueType, "issueType must not be null");
        if (issueType == PatientServiceIssueType.SCHEDULED_MISSED
                && (expectedTransmissionAt == null || nextTransmissionAt == null)) {
            throw new IllegalArgumentException(
                    "SCHEDULED_MISSED requires expectedTransmissionAt and nextTransmissionAt");
        }
        if (issueType == PatientServiceIssueType.SCHEDULED_MISSED
                && !nextTransmissionAt.isAfter(expectedTransmissionAt)) {
            throw new IllegalArgumentException("nextTransmissionAt must be after expectedTransmissionAt");
        }
        if (issueType == PatientServiceIssueType.TRANSMISSION_EVENTS_INCOMPLETE
                && (totalEventCount == null || receivedEventCount == null)) {
            throw new IllegalArgumentException(
                    "TRANSMISSION_EVENTS_INCOMPLETE requires totalEventCount and receivedEventCount");
        }
        if (totalEventCount != null && receivedEventCount != null
                && (totalEventCount < 0 || receivedEventCount < 0
                || receivedEventCount >= totalEventCount)) {
            throw new IllegalArgumentException("incomplete transmission event counts are invalid");
        }
    }

    /** 应传未传和低电量没有事件计数，保留这个构造器让上游调用保持简洁。 */
    public IssueDetectedPayload(
            String deviceRef,
            String sourceRecordRef,
            DeviceIdentityPayload device,
            PatientServiceIssueType issueType,
            Instant expectedTransmissionAt,
            Instant nextTransmissionAt
    ) {
        this(deviceRef, sourceRecordRef, device, issueType,
                expectedTransmissionAt, nextTransmissionAt, null, null);
    }
}
