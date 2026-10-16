package io.cardioops.domain.device;

import io.cardioops.domain.agent.ToolCapability;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * 一份设备族、型号和固件范围对应的能力画像。型号分支放在数据里，不写进图的 if/else。
 * profileId 发布以后含义不能改，案件上会记下它。生效时间是左闭右开的相邻区间，边界才不会两份都算命中。
 */
public record DeviceCapabilityProfile(
        String profileId,
        DeviceFamily family,
        String modelCode,
        FirmwareNumberRange firmwareRange,
        int priority,
        Instant effectiveFrom,
        Instant effectiveUntil,
        Set<ToolCapability> allowedTools,
        Set<String> sopTags,
        boolean writeActionsAllowed
) {
    public DeviceCapabilityProfile {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(family, "family must not be null");
        Objects.requireNonNull(modelCode, "modelCode must not be null");
        Objects.requireNonNull(firmwareRange, "firmwareRange must not be null");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
        Objects.requireNonNull(allowedTools, "allowedTools must not be null");
        Objects.requireNonNull(sopTags, "sopTags must not be null");
        if (profileId.isBlank() || modelCode.isBlank()) {
            throw new IllegalArgumentException("profileId and modelCode must not be blank");
        }
        if (effectiveUntil != null && !effectiveUntil.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveUntil must be after effectiveFrom");
        }
        allowedTools = Set.copyOf(allowedTools);
        sopTags = Set.copyOf(sopTags);
    }

    /** 同时检查设备族、型号、数字固件范围和事件发生时间，缺一项都不能匹配。 */
    boolean matches(DeviceDescriptor device, Instant at) {
        return family == device.family()
                && modelCode.equals(device.modelCode())
                && firmwareRange.contains(device.firmwareVersionNumber())
                && !at.isBefore(effectiveFrom)
                && (effectiveUntil == null || at.isBefore(effectiveUntil));
    }
}
