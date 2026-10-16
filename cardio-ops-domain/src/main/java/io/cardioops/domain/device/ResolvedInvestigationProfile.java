package io.cardioops.domain.device;

import io.cardioops.domain.agent.ToolCapability;

import java.util.Objects;
import java.util.Set;

/**
 * 解析结果。没唯一命中时 profileId 必须空着，不能造一个默认档案。
 * 没支持或冲突的结果如果还把写动作打开，构造时直接拒绝。
 *
 *
 * 默认工具等于假装核对过。没核对的语义不能进自动调查。
 */
public record ResolvedInvestigationProfile(
        ProfileResolutionStatus status,
        String profileId,
        Set<ToolCapability> allowedTools,
        Set<String> sopTags,
        boolean writeActionsAllowed,
        String reason
) {
    public ResolvedInvestigationProfile {
        Objects.requireNonNull(status, "status must not be null");
        allowedTools = Set.copyOf(allowedTools);
        sopTags = Set.copyOf(sopTags);
        Objects.requireNonNull(reason, "reason must not be null");
        if (status != ProfileResolutionStatus.SUPPORTED && writeActionsAllowed) {
            throw new IllegalArgumentException("unsupported profile must never allow write actions");
        }
    }
}
