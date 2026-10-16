package io.cardioops.infrastructure.kafka;

/**
 * Topic 名字只在这里拼。生产者和消费者各写一遍，重放和死信会对不上。
 */
public final class KafkaTopicNames {

    /** DevicePlatform 向 CardioOps 发布集成事件的主 Topic。 */
    public static final String INTEGRATION_EVENTS_V1 = "cardio-ops.integration-events.v1";
    /** 多次重试仍失败的消息进入 DLT，等待告警和人工处理。 */
    public static final String INTEGRATION_EVENTS_DLT_V1 = "cardio-ops.integration-events.v1.DLT";

    private KafkaTopicNames() {
    }
}
