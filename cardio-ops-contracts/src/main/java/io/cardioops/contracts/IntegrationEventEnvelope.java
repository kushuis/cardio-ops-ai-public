package io.cardioops.contracts;

import java.time.Instant;
import java.util.Objects;

/**
 * 跨系统事件信封。payload 承载业务数据，eventId 用于消费幂等，schemaVersion 支持协议演进，traceId 贯通上下游链路。
 * Outbox 由 Device Platform 维护，CardioOps 消费已经发布的事件。
 */
public record IntegrationEventEnvelope<T>(
        String eventId,
        int schemaVersion,
        String eventType,
        String traceId,
        Instant occurredAt,
        String correlationRef,
        T payload
) {
    /** V1 兼容构造器；V1 没有统一关联键，消费端只能使用明确可推导的旧字段。 */
    public IntegrationEventEnvelope(
            String eventId,
            int schemaVersion,
            String eventType,
            String traceId,
            Instant occurredAt,
            T payload
    ) {
        this(eventId, schemaVersion, eventType, traceId, occurredAt, null, payload);
    }

    /** 在消息进入 Kafka 之前尽早拦截缺失的追踪和幂等字段。 */
    public IntegrationEventEnvelope {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        if (schemaVersion >= 2 && (correlationRef == null || correlationRef.isBlank())) {
            throw new IllegalArgumentException("correlationRef is required since schemaVersion 2");
        }
    }
}
