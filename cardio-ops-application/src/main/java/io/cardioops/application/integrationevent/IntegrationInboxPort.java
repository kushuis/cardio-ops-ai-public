package io.cardioops.application.integrationevent;
/**
 * 按 eventId 占信封。返回 false 表示这个信封已经处理过，连档案都不用再解析。
 */


public interface IntegrationInboxPort {

    /** 依靠 event_id 唯一键原子注册；false 表示同一消息已经成功处理过。 */
    boolean tryRegister(IntegrationEventCommand command);

    /** 在同一事务末尾记录 ACCEPTED 或业务事实重复结果，供审计与指标核对。 */
    void markProcessed(String eventId, String processingResult);
}
