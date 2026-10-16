package io.cardioops.domain.scheduledmiss;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 一份档案把原始 triggerType 翻译成标准触发原因。profileId 和这次冻结的档案是同一份，运行中不能换口径。
 * 映射表里没有的整数就当不认识，返回空。废弃的旧码不为了兼容再开一条业务分支。
 *
 *
 * “其他”会把没核对的码说成某种恢复。空着，校验和事实里就只使用档案认过的原因。
 */
public record IcmTriggerPolicy(
        String profileId,
        IcmProtocolGeneration protocolGeneration,
        Map<Integer, IcmTransmissionTrigger> triggerMappings
) {
    public IcmTriggerPolicy {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(protocolGeneration, "protocolGeneration must not be null");
        triggerMappings = Map.copyOf(triggerMappings);
        if (profileId.isBlank() || triggerMappings.isEmpty()) {
            throw new IllegalArgumentException("ICM trigger policy must have id and mappings");
        }
    }

    /**
     * 映射里没有就返回空。不在这里猜这个整数是不是自动唤醒。
     */
    public Optional<IcmTransmissionTrigger> normalize(int rawTriggerType) {
        return Optional.ofNullable(triggerMappings.get(rawTriggerType));
    }
}
