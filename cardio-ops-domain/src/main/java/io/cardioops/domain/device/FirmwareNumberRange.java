package io.cardioops.domain.device;

/**
 * 固件数字版本的左闭右开区间。100 到 200 包含 100，不包含 200。相邻档案可以接上，边界不会叠两份。
 */
public record FirmwareNumberRange(long minInclusive, long maxExclusive) {
    public FirmwareNumberRange {
        if (minInclusive < 0 || maxExclusive <= minInclusive) {
            throw new IllegalArgumentException("invalid firmware number range");
        }
    }

    public boolean contains(long versionNumber) {
        return versionNumber >= minInclusive && versionNumber < maxExclusive;
    }

    public long width() {
        return maxExclusive - minInclusive;
    }
}
