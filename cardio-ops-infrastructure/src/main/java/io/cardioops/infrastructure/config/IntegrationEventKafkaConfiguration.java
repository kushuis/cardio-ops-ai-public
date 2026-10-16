package io.cardioops.infrastructure.config;

import io.cardioops.application.deadletter.DeadLetterEventPort;
import io.cardioops.application.deadletter.DeadLetterReplayService;
import io.cardioops.application.deadletter.DeadLetterReplayUseCase;
import io.cardioops.application.integrationevent.IntegrationEventPublisher;
import io.cardioops.infrastructure.kafka.KafkaIntegrationEventPublisher;
import io.cardioops.infrastructure.kafka.KafkaIntegrationProperties;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/**
 * 消费、重试和死信集中在这里。自动提交关掉，一条处理完再提交。失败后再试 2 次，一共 3 次，还不行才发到 DLT。
 * 死信消费者不再套同一套 recoverer，不然死信失败会写回同一条 DLT，转圈。
 * 重放将原始契约送回主 Topic。业务 Outbox 位于 Device Platform，CardioOps 不重复维护上游 Outbox。
 * 消费和发送上的 Span 跟着 Micrometer。要不要导出看 management.opentelemetry.enabled，也就是 CARDIO_OPS_OTEL_ENABLED，默认关着。
 *
 *
 * 复用的话，死信再失败还会被投进同一个 DLT。审计监听失败应该停在那里被人看，而不是再制造一条死信。
 */

@EnableKafka
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KafkaIntegrationProperties.class)
@ConditionalOnProperty(prefix = "cardio-ops.integration", name = "enabled", havingValue = "true")
public class IntegrationEventKafkaConfiguration {

    @Bean
    KafkaAdmin kafkaAdmin(KafkaIntegrationProperties properties) {
        return new KafkaAdmin(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getBootstrapServers()));
    }

    @Bean
    org.apache.kafka.clients.admin.NewTopic integrationEventsTopic(KafkaIntegrationProperties properties) {
        return TopicBuilder.name(properties.getTopic()).partitions(3).replicas(1).build();
    }

    @Bean
    org.apache.kafka.clients.admin.NewTopic integrationEventsDeadLetterTopic(
            KafkaIntegrationProperties properties
    ) {
        return TopicBuilder.name(properties.getDltTopic()).partitions(3).replicas(1).build();
    }

    @Bean
    ProducerFactory<String, String> integrationProducerFactory(KafkaIntegrationProperties properties) {
        Map<String, Object> configuration = new HashMap<>();
        configuration.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getBootstrapServers());
        configuration.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configuration.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configuration.put(ProducerConfig.ACKS_CONFIG, "all");
        configuration.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(configuration);
    }

    @Bean
    KafkaTemplate<String, String> integrationKafkaTemplate(
            ProducerFactory<String, String> integrationProducerFactory
    ) {
        var template = new KafkaTemplate<String, String>(integrationProducerFactory);
        // 开启后 Spring Kafka 会把当前 W3C traceparent 注入消息 Header；重放也沿用同一机制。
        template.setObservationEnabled(true);
        return template;
    }

    @Bean
    ConsumerFactory<String, String> integrationConsumerFactory(KafkaIntegrationProperties properties) {
        Map<String, Object> configuration = new HashMap<>();
        configuration.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getBootstrapServers());
        configuration.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configuration.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configuration.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        configuration.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(configuration);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> integrationKafkaListenerContainerFactory(
            ConsumerFactory<String, String> integrationConsumerFactory,
            KafkaTemplate<String, String> integrationKafkaTemplate,
            KafkaIntegrationProperties properties
    ) {
        var factory = baseFactory(integrationConsumerFactory);
        var recoverer = new DeadLetterPublishingRecoverer(
                integrationKafkaTemplate,
                (record, exception) -> new TopicPartition(properties.getDltTopic(), record.partition()));
        // 首次失败后再重试 2 次；仍失败才发布 DLT。总尝试次数为 3。
        var errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(250L, 2L));
        errorHandler.setCommitRecovered(true);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> deadLetterKafkaListenerContainerFactory(
            ConsumerFactory<String, String> integrationConsumerFactory
    ) {
        // DLT 审计不用主 Topic 的 recoverer，防止“DLT 消费失败又写回同一 DLT”的死循环。
        return baseFactory(integrationConsumerFactory);
    }

    private ConcurrentKafkaListenerContainerFactory<String, String> baseFactory(
            ConsumerFactory<String, String> consumerFactory
    ) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        // Consumer 从 Kafka Header 恢复 Trace Context，并为每次消息处理创建 consumer span。
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    @Bean
    IntegrationEventPublisher integrationEventPublisher(
            KafkaTemplate<String, String> integrationKafkaTemplate,
            KafkaIntegrationProperties properties
    ) {
        return new KafkaIntegrationEventPublisher(integrationKafkaTemplate, properties.getTopic());
    }

    @Bean
    DeadLetterReplayUseCase deadLetterReplayUseCase(
            DeadLetterEventPort deadLetters,
            IntegrationEventPublisher publisher,
            Clock clock
    ) {
        return new DeadLetterReplayService(deadLetters, publisher, clock);
    }
}
