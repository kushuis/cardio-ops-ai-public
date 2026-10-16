package io.cardioops.application.integrationevent;

/**
 * 应用层自己的事件种类。故意不直接引用契约枚举。协议升级时，用例不必跟着消息 DTO 一起改。
 */
public enum IntegrationEventKind {
    /** DevicePlatform 已确认存在需要调查的问题，后续可升级为 Incident。 */
    ISSUE_DETECTED
}
