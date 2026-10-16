package io.cardioops.domain.incident;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 一条不能改写的证据。事件和证据类型组成唯一键，同一条消息再来不会再插一条。
 * 定时实传先到、案件还没建出来时，incidentId 可以先空着，等关联窗口对上再挂。
 */
public record IncidentEvidence(
        String evidenceId,
        String evidenceKey,
        String incidentId,
        String sourceEventId,
        EvidenceType type,
        String deviceRef,
        String sourceRecordRef,
        String correlationRef,
        Instant occurredAt,
        Map<String, String> auditFacts,
        Instant createdAt
) {
    public IncidentEvidence {
        Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        Objects.requireNonNull(evidenceKey, "evidenceKey must not be null");
        Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(deviceRef, "deviceRef must not be null");
        Objects.requireNonNull(sourceRecordRef, "sourceRecordRef must not be null");
        Objects.requireNonNull(correlationRef, "correlationRef must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        auditFacts = Map.copyOf(auditFacts);
    }
}
