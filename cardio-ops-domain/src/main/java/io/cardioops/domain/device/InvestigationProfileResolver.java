package io.cardioops.domain.device;

import io.cardioops.domain.agent.ToolCapability;

import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;

/**
 * 给一台设备挑唯一一份还在生效的 Profile。族、型号、数字固件、发生时间都要中。
 * 多份同时中时，先看人工优先级，再看固件范围更窄的那份。前两名优先级和具体度还一样，就当冲突，不随机挑一个。
 * 对不上或冲突时，profileId 留空，只给识别设备和提示不支持的最小工具，并且不允许写动作。
 *
 *
 * 先加载的那份没有业务含义。打平说明配置自己没说清楚，降级比猜一个安全。
 */
public final class InvestigationProfileResolver {

    private static final Set<ToolCapability> SAFE_FALLBACK_TOOLS = Set.of(
            ToolCapability.GET_DEVICE_CONTEXT,
            ToolCapability.GET_SUPPORTED_PROFILE_HELP
    );

    private final DeviceCapabilityRegistry registry;

    public InvestigationProfileResolver(DeviceCapabilityRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    /**
     * 用事件发生时间，不用“现在”。后来才发布的档案不该套到旧事件上。
     */
    public ResolvedInvestigationProfile resolve(DeviceDescriptor device, Instant at) {
        // 先做硬条件过滤：设备族、型号、数字固件和生效时间必须全部匹配。
        var matches = registry.profiles().stream()
                .filter(profile -> profile.matches(device, at))
                // 同时命中时先选人工优先级更高者，再选固件范围更窄、更具体者。
                .sorted(Comparator.comparingInt(DeviceCapabilityProfile::priority).reversed()
                        .thenComparingLong(profile -> profile.firmwareRange().width()))
                .toList();

        if (matches.isEmpty()) {
            return fallback(ProfileResolutionStatus.UNSUPPORTED_PROFILE,
                    "no active profile matches family/model/firmware");
        }

        var selected = matches.getFirst();
        if (matches.size() > 1) {
            var runnerUp = matches.get(1);
            // 如果前两名优先级和具体度仍相同，配置无法给出确定答案，宁可降级也不随机选。
            if (selected.priority() == runnerUp.priority()
                    && selected.firmwareRange().width() == runnerUp.firmwareRange().width()) {
                return fallback(ProfileResolutionStatus.AMBIGUOUS_PROFILE,
                        "multiple profiles have the same priority and specificity");
            }
        }

        return new ResolvedInvestigationProfile(
                ProfileResolutionStatus.SUPPORTED,
                selected.profileId(),
                selected.allowedTools(),
                selected.sopTags(),
                selected.writeActionsAllowed(),
                "matched active profile " + selected.profileId()
        );
    }

    private ResolvedInvestigationProfile fallback(ProfileResolutionStatus status, String reason) {
        // 降级工具只能帮助识别设备和提示“不支持”，不会查询高风险数据或产生写类动作。
        return new ResolvedInvestigationProfile(
                status,
                null,
                SAFE_FALLBACK_TOOLS,
                Set.of("unsupported-profile"),
                false,
                reason
        );
    }
}
