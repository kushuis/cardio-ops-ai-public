package io.cardioops.application.integrationevent;

/**
 * 进门用例的口。实现外面再套本地事务，投影失败时 Inbox 注册也要一起撤。
 */
public interface IntegrationEventIntakeUseCase {
    IntegrationIntakeResult consume(IntegrationEventCommand command);
}
