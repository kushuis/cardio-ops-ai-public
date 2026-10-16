package io.cardioops.infrastructure.integration;

/**
 * 契约认不出来就抛。Kafka 会重试，耗尽后进 DLT。在这里吞掉，脏消息会被当成处理成功。
 */
public final class IntegrationEventContractException extends RuntimeException {
    public IntegrationEventContractException(String message, Throwable cause) {
        super(message, cause);
    }

    public IntegrationEventContractException(String message) {
        super(message);
    }
}
