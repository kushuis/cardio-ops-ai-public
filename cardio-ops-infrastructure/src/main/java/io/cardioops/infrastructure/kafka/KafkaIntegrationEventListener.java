package io.cardioops.infrastructure.kafka;

import io.cardioops.application.integrationevent.IntegrationEventIntakeUseCase;
import io.cardioops.infrastructure.integration.IntegrationEventJsonCodec;
import io.cardioops.infrastructure.integration.IntegrationEventMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Worker 上的 Kafka 入口。只有 runtime.role 是 worker、而且集成开关开着，这个监听器才会挂上。
 *
 * <p>Outbox 不在这个服务里。Outbox 在 DevicePlatform，它负责把事件可靠地发出去。
 * 消费端使用 Inbox 和业务键实现双重幂等，失败事件进入 DLT，重放事件重新进入标准消费路径。
 * 监听器不直接重放或修改业务状态，避免绕过 Kafka 消费语义与幂等边界。</p>
 *
 * <p>
 * 吞掉以后 Kafka 以为这条成功了，重试和 DLT 都不会发生。抛出去，统一错误处理才能重试，耗尽后再进死信。</p>
 */
@Component
@ConditionalOnExpression("'${cardio-ops.runtime.role:}' == 'worker' && ${cardio-ops.integration.enabled:false}")
public class KafkaIntegrationEventListener {

    private static final Logger log = LoggerFactory.getLogger(KafkaIntegrationEventListener.class);

    private final IntegrationEventJsonCodec codec;
    private final IntegrationEventIntakeUseCase intakeUseCase;
    private final IntegrationEventMetrics metrics;

    public KafkaIntegrationEventListener(
            IntegrationEventJsonCodec codec,
            IntegrationEventIntakeUseCase intakeUseCase,
            IntegrationEventMetrics metrics
    ) {
        this.codec = codec;
        this.intakeUseCase = intakeUseCase;
        this.metrics = metrics;
    }

    @KafkaListener(
            topics = "${cardio-ops.integration.kafka.topic}",
            groupId = "${cardio-ops.integration.kafka.consumer-group}",
            containerFactory = "integrationKafkaListenerContainerFactory"
    )
    /**
     * 一条消息走一遍解码和进门。日志只留 topic、分区、offset 和事件身份，不打正文。
     * 正文里是业务事实，接收日志把 payload 打出去，事实就进了日志。
     *
     * <p>解码后交由 intake 处理，eventId 与业务键的双重幂等均在该用例内完成。
     * 这里的运行时异常继续往外抛，不包装成处理完成。</p>
     *
     * <p>
     * 重放只是把当初的原始契约重新发布回来。它得再走解码和 Inbox，不能绕过这个入口直接改业务。</p>
     */
    public void onMessage(ConsumerRecord<String, String> record) {
        long startedAt = System.nanoTime();
        log.debug(
                "kafka integration event received topic={} partition={} offset={}",
                record.topic(), record.partition(), record.offset());
        try {
            var command = codec.decode(record.value());
            log.debug(
                    "integration event contract decoded eventId={} traceId={} eventType={} deviceRef={} "
                            + "schemaVersion={}",
                    command.eventId(), command.traceId(), command.eventType(), command.deviceRef(),
                    command.schemaVersion());

            var result = intakeUseCase.consume(command);
            metrics.recordIntake(command, result);
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.info(
                    "kafka integration event handled topic={} partition={} offset={} eventId={} traceId={} "
                            + "eventType={} deviceRef={} status={} profileId={} durationMs={}",
                    record.topic(), record.partition(), record.offset(), command.eventId(), command.traceId(),
                    command.eventType(), command.deviceRef(), result.status(), result.profileId(), durationMs);
        } catch (RuntimeException exception) {
            // 记一笔就抛回去。统一 ErrorHandler 靠这个异常做重试，耗尽后写 DLT。
            log.warn(
                    "kafka integration event handling failed topic={} partition={} offset={} errorType={}",
                    record.topic(), record.partition(), record.offset(), exception.getClass().getSimpleName());
            throw exception;
        }
    }
}
