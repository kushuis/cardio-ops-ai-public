package io.cardioops.domain.lowbattery;

import java.time.Instant;

/** 低电量告警之后的一次已结束传输；原始触发码在本场景不参与恢复判定。 */
public record LowBatteryTransmissionObservation(
        /** DevicePlatform 传输记录引用，供最终结果引用，不能由模型编造。 */
        String evidenceRef,
        /** GATEWAY 或 MINI_PROGRAM；渠道本身不能证明电量因果。 */
        String channel,
        /** 该批传输真正结束的时间，用于选出低电量后的最新一批。 */
        Instant completedAt,
        /** 设备声明该批应上传的事件总数。 */
        int totalEventCount,
        /** 云端实际收到的事件数。 */
        int receivedEventCount
) {
    public boolean complete() {
        return totalEventCount > 0 && receivedEventCount == totalEventCount;
    }
}
