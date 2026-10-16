package io.cardioops.application.deadletter;

import java.time.Instant;
import java.util.Optional;
/**
 * 按 deadLetterId 幂等保存死信。已经重放过的再标一次，不应该把状态打回去。
 */


public interface DeadLetterEventPort {
    /** 按 deadLetterId 幂等保存 Kafka DLT 审计记录。 */
    void saveIfAbsent(DeadLetterEvent event);

    /** 重放前按稳定 ID 查询原始死信，避免由调用方伪造 payload。 */
    Optional<DeadLetterEvent> find(String deadLetterId);

    /** broker 确认重新发布后标记 REPLAYED；重复调用不得再次发布。 */
    void markReplayed(String deadLetterId, Instant replayedAt);
}
