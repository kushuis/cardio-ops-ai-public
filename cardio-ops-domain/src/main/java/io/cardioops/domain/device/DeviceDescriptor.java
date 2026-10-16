package io.cardioops.domain.device;

import java.util.Objects;

/**
 * 挑调查策略时需要的最小设备画像。固件同时留展示版本和数字版本，比较范围用数字，免得 1.10 被字符串比成比 1.9 小。
 */
public record DeviceDescriptor(
        DeviceFamily family,
        String modelCode,
        String firmwareVersion,
        long firmwareVersionNumber
) {
    public DeviceDescriptor {
        Objects.requireNonNull(family, "family must not be null");
        Objects.requireNonNull(modelCode, "modelCode must not be null");
        Objects.requireNonNull(firmwareVersion, "firmwareVersion must not be null");
        if (modelCode.isBlank() || firmwareVersion.isBlank() || firmwareVersionNumber < 0) {
            throw new IllegalArgumentException("device descriptor contains invalid values");
        }
    }
}
