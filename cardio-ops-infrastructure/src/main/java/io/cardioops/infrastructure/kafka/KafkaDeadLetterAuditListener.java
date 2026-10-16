package io.cardioops.infrastructure.kafka;

import io.cardioops.application.deadletter.DeadLetterEvent;
import io.cardioops.application.deadletter.DeadLetterEventPort;
import io.cardioops.infrastructure.integration.IntegrationEventJsonCodec;
import io.cardioops.infrastructure.integration.IntegrationEventMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

/**
 * 把 DLT 镜像进表，才能按 deadLetterId 查看和重放。解析不出 eventId 也要落下原始载荷，不然坏 JSON 永远只躺在 Kafka 里。
 * 这个监听不负责重放，也不改业务。重放是另一条把原始契约送回主 Topic 的路。
 */

@Component
@ConditionalOnExpression("'${cardio-ops.runtime.role:}' == 'worker' && ${cardio-ops.integration.enabled:false}")
public class KafkaDeadLetterAuditListener {

    private static final Logger log = LoggerFactory.getLogger(KafkaDeadLetterAuditListener.class);

    private final DeadLetterEventPort deadLetters;
    private final IntegrationEventJsonCodec codec;
    private final Clock clock;
    private final IntegrationEventMetrics metrics;

    public KafkaDeadLetterAuditListener(
            DeadLetterEventPort deadLetters,
            IntegrationEventJsonCodec codec,
            Clock clock,
            IntegrationEventMetrics metrics
    ) {
        this.deadLetters = deadLetters;
        this.codec = codec;
        this.clock = clock;
        this.metrics = metrics;
    }

    @KafkaListener(
            topics = "${cardio-ops.integration.kafka.dlt-topic}",
            groupId = "${cardio-ops.integration.kafka.consumer-group}-dlt-audit",
            containerFactory = "deadLetterKafkaListenerContainerFactory"
    )
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        String originalEventId = tryReadEventId(record.value());
        String originalTopic = headerEndingWith(record, "original-topic");
        String failureType = headerEndingWith(record, "exception-fqcn");
        String failureMessage = headerEndingWith(record, "exception-message");
        String deadLetterId = record.topic() + ":" + record.partition() + ":" + record.offset();

        deadLetters.saveIfAbsent(new DeadLetterEvent(
                deadLetterId,
                originalEventId,
                originalTopic == null ? removeDltSuffix(record.topic()) : originalTopic,
                record.value(),
                failureType == null ? "UNKNOWN" : failureType,
                failureMessage,
                clock.instant(),
                "PENDING"
        ));
        metrics.recordDeadLetter(failureType);
        // 不打印 rawPayload 和 failureMessage，避免业务数据或底层敏感信息进入普通应用日志。
        log.warn(
                "dead letter audited deadLetterId={} originalEventId={} originalTopic={} failureType={} "
                        + "dltTopic={} partition={} offset={}",
                deadLetterId, originalEventId,
                originalTopic == null ? removeDltSuffix(record.topic()) : originalTopic,
                failureType == null ? "UNKNOWN" : failureType,
                record.topic(), record.partition(), record.offset());
    }

    private String tryReadEventId(String rawPayload) {
        try {
            return codec.decode(rawPayload).eventId();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String headerEndingWith(ConsumerRecord<String, String> record, String suffix) {
        for (Header header : record.headers()) {
            if (header.key().endsWith(suffix) && header.value() != null) {
                return new String(header.value(), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private String removeDltSuffix(String topic) {
        return topic.endsWith(".DLT") ? topic.substring(0, topic.length() - 4) : topic;
    }
}
