package io.cardioops.application.integrationevent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 已经由 DevicePlatform 确认的问题。应传未传必须带应传时间，时间来自设备时间，不用消息到达时间。
 */
public record IssueDetectedDetails(
        DetectedIssueType issueType,
        Instant expectedTransmissionAt,
        Instant nextTransmissionAt,
        Integer totalEventCount,
        Integer receivedEventCount
) implements IntegrationEventDetails {
    public IssueDetectedDetails {
        Objects.requireNonNull(issueType, "issueType must not be null");
        if (issueType == DetectedIssueType.SCHEDULED_MISSED
                && (expectedTransmissionAt == null || nextTransmissionAt == null)) {
            throw new IllegalArgumentException(
                    "SCHEDULED_MISSED requires expectedTransmissionAt and nextTransmissionAt");
        }
        if (issueType == DetectedIssueType.SCHEDULED_MISSED
                && !nextTransmissionAt.isAfter(expectedTransmissionAt)) {
            throw new IllegalArgumentException("nextTransmissionAt must be after expectedTransmissionAt");
        }
        if (issueType == DetectedIssueType.TRANSMISSION_EVENTS_INCOMPLETE
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

    @Override
    public Map<String, String> auditFacts() {
        var facts = new LinkedHashMap<String, String>();
        facts.put("issueType", issueType.name());
        if (expectedTransmissionAt != null) {
            facts.put("expectedTransmissionAt", expectedTransmissionAt.toString());
        }
        if (nextTransmissionAt != null) {
            facts.put("nextTransmissionAt", nextTransmissionAt.toString());
        }
        if (totalEventCount != null) {
            facts.put("totalEventCount", totalEventCount.toString());
        }
        if (receivedEventCount != null) {
            facts.put("receivedEventCount", receivedEventCount.toString());
        }
        return Map.copyOf(facts);
    }

    /** 应传未传和低电量不需要事件计数。 */
    public IssueDetectedDetails(
            DetectedIssueType issueType,
            Instant expectedTransmissionAt,
            Instant nextTransmissionAt
    ) {
        this(issueType, expectedTransmissionAt, nextTransmissionAt, null, null);
    }
}
