package io.cardioops.infrastructure.integration;

import io.cardioops.application.integrationevent.DetectedIssueType;
import io.cardioops.application.integrationevent.IntegrationEventCommand;
import io.cardioops.application.integrationevent.IntegrationEventDetails;
import io.cardioops.application.integrationevent.IntegrationEventKind;
import io.cardioops.application.integrationevent.IssueDetectedDetails;
import io.cardioops.contracts.DeviceFamilyCode;
import io.cardioops.contracts.IntegrationEventType;
import io.cardioops.contracts.IssueDetectedPayload;
import io.cardioops.contracts.RemoteMonitoringEventPayload;
import io.cardioops.domain.device.DeviceDescriptor;
import io.cardioops.domain.device.DeviceFamily;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * 把 JSON 信封译成强类型命令。业务代码不再翻 Map。
 * Topic 只接收三类异常；正常传输由调查 Agent 通过 MCP 按需读取。
 * 译不出来就抛契约异常，交给重试和 DLT，不把半截对象送进 Inbox。
 *
 *
 * 协议升级时，消息 DTO 会变。边界上翻译一次，里面的用例可以继续用自己的类型。
 */
public final class IntegrationEventJsonCodec {

    private final ObjectMapper objectMapper;

    public IntegrationEventJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public IntegrationEventCommand decode(String rawJson) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            String eventId = requiredText(root, "eventId");
            int schemaVersion = root.required("schemaVersion").intValue();
            IntegrationEventType eventType = IntegrationEventType.valueOf(requiredText(root, "eventType"));
            String traceId = requiredText(root, "traceId");
            Instant occurredAt = Instant.parse(requiredText(root, "occurredAt"));
            String correlationRef = schemaVersion == 2
                    ? requiredText(root, "correlationRef")
                    : optionalText(root, "correlationRef");

            RemoteMonitoringEventPayload payload = decodePayload(eventType, root.required("payload"));
            if (correlationRef == null) {
                correlationRef = legacyCorrelationRef(payload);
            }
            validateCorrelation(correlationRef, payload);

            var device = payload.device();
            var descriptor = new DeviceDescriptor(
                    mapFamily(device.family()), device.modelCode(),
                    device.firmwareVersion(), device.firmwareVersionNumber());
            return new IntegrationEventCommand(
                    eventId, schemaVersion, IntegrationEventKind.valueOf(eventType.name()), traceId,
                    correlationRef, payload.deviceRef(), payload.sourceRecordRef(), occurredAt,
                    descriptor, details(payload));
        } catch (IntegrationEventContractException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IntegrationEventContractException("invalid integration event contract", exception);
        }
    }

    private RemoteMonitoringEventPayload decodePayload(IntegrationEventType type, JsonNode node) {
        return objectMapper.treeToValue(node, IssueDetectedPayload.class);
    }

    private IntegrationEventDetails details(RemoteMonitoringEventPayload payload) {
        if (payload instanceof IssueDetectedPayload issue) {
            return new IssueDetectedDetails(
                    DetectedIssueType.valueOf(issue.issueType().name()),
                    issue.expectedTransmissionAt(), issue.nextTransmissionAt(),
                    issue.totalEventCount(), issue.receivedEventCount());
        }
        throw new IntegrationEventContractException("unsupported payload type: " + payload.getClass().getName());
    }

    /**
     * 三类问题各自使用稳定的业务关联键；不允许模型或生产者随意拼接查询范围。
     */
    private void validateCorrelation(String correlationRef, RemoteMonitoringEventPayload payload) {
        if (payload instanceof IssueDetectedPayload issue
                && issue.issueType() == io.cardioops.contracts.PatientServiceIssueType.SCHEDULED_MISSED) {
            String expected = monitoringCorrelation(issue.deviceRef(), issue.expectedTransmissionAt());
            if (!expected.equals(correlationRef)) {
                throw new IntegrationEventContractException(
                        "SCHEDULED_MISSED correlationRef does not match expectedTransmissionAt");
            }
        }
        if (payload instanceof IssueDetectedPayload issue
                && issue.issueType() == io.cardioops.contracts.PatientServiceIssueType.LOW_BATTERY) {
            String expected = "BATTERY:" + issue.deviceRef() + ":" + issue.sourceRecordRef();
            if (!expected.equals(correlationRef)) {
                throw new IntegrationEventContractException(
                        "LOW_BATTERY correlationRef does not match sourceRecordRef");
            }
        }
        if (payload instanceof IssueDetectedPayload issue
                && issue.issueType()
                == io.cardioops.contracts.PatientServiceIssueType.TRANSMISSION_EVENTS_INCOMPLETE) {
            String expected = "TRANSMISSION:" + issue.deviceRef() + ":" + issue.sourceRecordRef();
            if (!expected.equals(correlationRef)) {
                throw new IntegrationEventContractException(
                        "TRANSMISSION_EVENTS_INCOMPLETE correlationRef does not match sourceRecordRef");
            }
        }
    }

    private String legacyCorrelationRef(RemoteMonitoringEventPayload payload) {
        if (payload instanceof IssueDetectedPayload issue) {
            if (issue.issueType() == io.cardioops.contracts.PatientServiceIssueType.SCHEDULED_MISSED) {
                return monitoringCorrelation(issue.deviceRef(), issue.expectedTransmissionAt());
            }
            return issue.issueType() == io.cardioops.contracts.PatientServiceIssueType.LOW_BATTERY
                    ? "BATTERY:" + issue.deviceRef() + ":" + issue.sourceRecordRef()
                    : "TRANSMISSION:" + issue.deviceRef() + ":" + issue.sourceRecordRef();
        }
        throw new IntegrationEventContractException("unsupported payload type: " + payload.getClass().getName());
    }

    private String monitoringCorrelation(String deviceRef, Instant expectedTransmissionAt) {
        return "MONITORING:" + deviceRef + ":" + expectedTransmissionAt.getEpochSecond();
    }

    private DeviceFamily mapFamily(DeviceFamilyCode family) {
        return DeviceFamily.valueOf(family.name());
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.required(field).textValue();
        if (value == null || value.isBlank()) {
            throw new IntegrationEventContractException(field + " must be a non-blank string");
        }
        return value;
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.textValue();
        return text == null || text.isBlank() ? null : text;
    }
}
