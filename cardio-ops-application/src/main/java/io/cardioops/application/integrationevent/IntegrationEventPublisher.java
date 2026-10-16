package io.cardioops.application.integrationevent;

/**
 * 把原始契约重新发布出去的口。死信重放用它回到主 Topic，不在这里改业务。
 */
public interface IntegrationEventPublisher {
    /** eventKey 用于 Kafka 分区，rawPayload 必须保持原始契约内容。 */
    void publish(String eventKey, String rawPayload);
}
