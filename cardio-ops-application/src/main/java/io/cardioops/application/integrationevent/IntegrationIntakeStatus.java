package io.cardioops.application.integrationevent;

/**
 * 三种进门结果：接受、同一个信封又来了、另一封信说的是同一件事实。
 */
public enum IntegrationIntakeStatus {
    /** 第一次看到该事件和业务事实，已成功建立新的集成事件入口记录。 */
    ACCEPTED,
    /** event_inbox 已存在相同 eventId，说明是同一个 Kafka 事件再次投递。 */
    DUPLICATE_EVENT,
    /** eventId 不同，但 businessKey 已存在，说明两个事件描述了同一个业务事实。 */
    DUPLICATE_BUSINESS_FACT
}
