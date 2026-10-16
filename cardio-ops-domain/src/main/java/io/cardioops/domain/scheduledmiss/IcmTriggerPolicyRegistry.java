package io.cardioops.domain.scheduledmiss;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 按冻住的 profileId 找翻译规则。找不到就空着，调用方走安全降级。ICD 或未知档案不在这里猜一套 ICM 码表。
 * 同一个 profileId 配两份规则，启动时失败。
 *
 *
 * 通用码表等于把没核对的型号说成核对过。空结果让调查转人工。
 */
public final class IcmTriggerPolicyRegistry {

    private final Map<String, IcmTriggerPolicy> policies;

    public IcmTriggerPolicyRegistry(List<IcmTriggerPolicy> policies) {
        Objects.requireNonNull(policies, "policies must not be null");
        try {
            this.policies = policies.stream().collect(Collectors.toUnmodifiableMap(
                    IcmTriggerPolicy::profileId, Function.identity()));
        } catch (IllegalStateException duplicate) {
            throw new IllegalArgumentException("duplicate ICM trigger policy profileId", duplicate);
        }
    }

    /** 找不到时返回 empty，让调用方安全降级到人工复核。 */
    public Optional<IcmTriggerPolicy> find(String profileId) {
        return Optional.ofNullable(profileId).map(policies::get);
    }
}
