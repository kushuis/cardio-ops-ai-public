package io.cardioops.infrastructure.integration;

import io.cardioops.application.integrationevent.DetectedIssueType;
import io.cardioops.application.integrationevent.IssueDetectedDetails;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrationEventJsonCodecTest {

    private final IntegrationEventJsonCodec codec = new IntegrationEventJsonCodec(
            JsonMapper.builder().findAndAddModules().build());

    @Test
    void decodesTransmissionEventsIncompleteAsAnAbnormalIssue() {
        var command = codec.decode(incompleteTransmissionJson("event-001", 2));
        assertThat(command.details()).isInstanceOfSatisfying(IssueDetectedDetails.class, details -> {
            assertThat(details.issueType()).isEqualTo(DetectedIssueType.TRANSMISSION_EVENTS_INCOMPLETE);
            assertThat(details.totalEventCount()).isEqualTo(5);
            assertThat(details.receivedEventCount()).isEqualTo(3);
        });
    }

    @Test
    void decodesScheduledMissedFromRealDevicePlatformBusinessName() {
        var command = codec.decode(scheduledMissedJson("MONITORING:device-ref-001:1788480000"));
        assertThat(command.details()).isInstanceOfSatisfying(IssueDetectedDetails.class, details -> {
            assertThat(details.issueType()).isEqualTo(DetectedIssueType.SCHEDULED_MISSED);
            assertThat(details.expectedTransmissionAt()).hasToString("2026-09-04T00:00:00Z");
            assertThat(details.nextTransmissionAt()).hasToString("2026-09-05T00:00:00Z");
        });
    }

    @Test
    void rejectsInventedIssueAliasInsteadOfSilentlyMappingIt() {
        String json = scheduledMissedJson("MONITORING:device-ref-001:1788480000")
                .replace("SCHEDULED_MISSED", "EXPECTED_TRANSMISSION_MISSING");
        assertThatThrownBy(() -> codec.decode(json)).isInstanceOf(IntegrationEventContractException.class);
    }

    @Test
    void rejectsScheduledCorrelationThatDoesNotMatchDeviceTime() {
        assertThatThrownBy(() -> codec.decode(scheduledMissedJson("MONITORING:device-ref-001:wrong")))
                .isInstanceOf(IntegrationEventContractException.class).hasMessageContaining("does not match");
    }

    @Test
    void rejectsIncompleteTransmissionWithoutMissingEvents() {
        String json = incompleteTransmissionJson("event-invalid", 2)
                .replace("\"receivedEventCount\":3", "\"receivedEventCount\":5");
        assertThatThrownBy(() -> codec.decode(json)).isInstanceOf(IntegrationEventContractException.class);
    }

    @Test
    void rejectsFutureSchemaVersionBeforeBusinessProcessing() {
        assertThatThrownBy(() -> codec.decode(incompleteTransmissionJson("event-future", 3)))
                .isInstanceOf(IntegrationEventContractException.class);
    }

    private String scheduledMissedJson(String correlationRef) {
        return """
                {
                  "eventId":"event-issue-001","schemaVersion":2,
                  "eventType":"ISSUE_DETECTED","traceId":"trace-001",
                  "occurredAt":"2026-09-04T00:10:00Z",
                  "correlationRef":"%s",
                  "payload":{
                    "deviceRef":"device-ref-001","sourceRecordRef":"patient-service-001",
                    "device":{"family":"ICM","modelCode":"DEMO_ICM_V1","firmwareVersion":"1.2.0","firmwareVersionNumber":120},
                    "issueType":"SCHEDULED_MISSED",
                    "expectedTransmissionAt":"2026-09-04T00:00:00Z",
                    "nextTransmissionAt":"2026-09-05T00:00:00Z"
                  }
                }
                """.formatted(correlationRef);
    }

    private String incompleteTransmissionJson(String eventId, int schemaVersion) {
        return """
                {
                  "eventId":"%s","schemaVersion":%d,
                  "eventType":"ISSUE_DETECTED","traceId":"trace-001",
                  "occurredAt":"2026-09-04T00:20:00Z",
                  "correlationRef":"TRANSMISSION:device-ref-001:session-001",
                  "payload":{
                    "deviceRef":"device-ref-001","sourceRecordRef":"session-001",
                    "device":{"family":"ICM","modelCode":"DEMO_ICM_V1","firmwareVersion":"1.2.0","firmwareVersionNumber":120},
                    "issueType":"TRANSMISSION_EVENTS_INCOMPLETE",
                    "totalEventCount":5,"receivedEventCount":3
                  }
                }
                """.formatted(eventId, schemaVersion);
    }
}
