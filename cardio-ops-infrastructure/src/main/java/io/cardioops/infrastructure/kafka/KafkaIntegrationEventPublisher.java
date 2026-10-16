package io.cardioops.infrastructure.kafka;

import io.cardioops.application.integrationevent.IntegrationEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.TimeUnit;

/**
 * 重放用的发布器。等 broker 确认之后才告诉上层成功。没确认就标成已重放，消息可能根本没回去。
 */
public final class KafkaIntegrationEventPublisher implements IntegrationEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaIntegrationEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;

    public KafkaIntegrationEventPublisher(KafkaTemplate<String, String> kafkaTemplate, String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public void publish(String key, String rawPayload) {
        try {
            log.debug("integration event publish started topic={} key={}", topic, key);
            var metadata = kafkaTemplate.send(topic, key, rawPayload)
                    .get(10, TimeUnit.SECONDS)
                    .getRecordMetadata();
            log.info(
                    "integration event publish acknowledged topic={} key={} partition={} offset={}",
                    metadata.topic(), key, metadata.partition(), metadata.offset());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("integration event publish interrupted topic={} key={}", topic, key);
            throw new IllegalStateException("publishing integration event was interrupted", exception);
        } catch (Exception exception) {
            log.warn(
                    "integration event publish failed topic={} key={} errorType={}",
                    topic, key, exception.getClass().getSimpleName());
            throw new IllegalStateException("cannot publish integration event", exception);
        }
    }
}
