package io.cardioops.contracts;

import java.util.Objects;

/**
 * 事件发生时的设备快照。同时带展示版本和数字版本。比较固件范围用数字，免得 1.10 被字符串比成比 1.9 小。
 */
public record DeviceIdentityPayload(
        DeviceFamilyCode family,
        String modelCode,
        String firmwareVersion,
        long firmwareVersionNumber
) {
    public DeviceIdentityPayload {
        Objects.requireNonNull(family, "family must not be null");
        modelCode = ContractValidation.requireReference(modelCode, "modelCode");
        firmwareVersion = ContractValidation.requireText(firmwareVersion, "firmwareVersion", 64);
        if (firmwareVersionNumber < 0) {
            throw new IllegalArgumentException("firmwareVersionNumber must not be negative");
        }
    }
}
