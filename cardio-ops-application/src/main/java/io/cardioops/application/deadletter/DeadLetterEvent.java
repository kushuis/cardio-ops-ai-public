package io.cardioops.application.deadletter;

import java.time.Instant;

/**
 * 一条死信的可查询记录。deadLetterId 由 DLT 的 topic、分区和 offset 组成，审计消费者重复读也不会插两行。
 * 重放状态只有待重放和已重放。
 */
public record DeadLetterEvent(
        String deadLetterId,
        String originalEventId,
        String originalTopic,
        String rawPayload,
        String failureType,
        String failureMessage,
        Instant failedAt,
        String replayStatus
) {
}
