package io.cardioops.application.deadletter;

import io.cardioops.application.integrationevent.IntegrationEventPublisher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Objects;

/**
 * 重放只把当初落下来的原始契约重新发布到主 Topic。发布成功才把记录标成已重放。
 * 它不调用进门用例。绕过 Kafka、解码和 Inbox 两道幂等，重复消息会直接改业务。
 * Outbox 位于 Device Platform；本服务负责将死信重新投递到 CardioOps 的标准消费路径。
 *
 *
 * 少的那一次正好是契约校验和两道幂等。直接调用例，坏消息和重复信封都会抄近路。
 */
public final class DeadLetterReplayService implements DeadLetterReplayUseCase {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterReplayService.class);

    private final DeadLetterEventPort deadLetters;
    private final IntegrationEventPublisher publisher;
    private final Clock clock;

    public DeadLetterReplayService(
            DeadLetterEventPort deadLetters,
            IntegrationEventPublisher publisher,
            Clock clock
    ) {
        this.deadLetters = Objects.requireNonNull(deadLetters);
        this.publisher = Objects.requireNonNull(publisher);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    /**
     * 先读审计记录，不允许调用方提交任意 JSON。已经重放过就返回 ALREADY_REPLAYED，不再发第二次。
     */
    public DeadLetterReplayResult replay(String deadLetterId) {
        log.info("dead letter replay requested deadLetterId={}", deadLetterId);
        // 先读数据库审计记录，不允许调用方提交一段任意 JSON 作为“重放”。
        var deadLetter = deadLetters.find(deadLetterId)
                .orElseThrow(() -> new IllegalArgumentException("dead letter not found: " + deadLetterId));
        if ("REPLAYED".equals(deadLetter.replayStatus())) {
            log.info(
                    "dead letter replay skipped reason=already_replayed deadLetterId={} originalEventId={}",
                    deadLetterId, deadLetter.originalEventId());
            return new DeadLetterReplayResult(deadLetterId, deadLetter.originalEventId(), "ALREADY_REPLAYED");
        }
        // 重新发布原始契约，让它再次经过 JSON 校验、正常 Consumer 和 Inbox 双层幂等。
        // 绝不能为了省事直接调用 IntegrationEventIntakeService 绕过正常入口。
        String key = deadLetter.originalEventId() == null ? deadLetter.deadLetterId() : deadLetter.originalEventId();
        publisher.publish(key, deadLetter.rawPayload());
        deadLetters.markReplayed(deadLetterId, clock.instant());
        log.info(
                "dead letter replay completed deadLetterId={} originalEventId={} originalTopic={}",
                deadLetterId, deadLetter.originalEventId(), deadLetter.originalTopic());
        return new DeadLetterReplayResult(deadLetterId, deadLetter.originalEventId(), "REPLAYED");
    }
}
