package io.cardioops.domain.scheduledmiss;

/**
 * 一次上传为什么发生。磁铁是患者主动的，只能证明链路通。定时、事件、告警才能证明设备自己醒过。
 * 通道相同不代表触发原因相同。
 */
public enum IcmTransmissionTrigger {
    /** 患者用磁铁主动激活设备；能证明链路可用，但不能证明自动唤醒正常。 */
    MAGNET(false),
    /** 到达家庭监护计划时间后自动广播。 */
    SCHEDULED(true),
    /** ICM V2 检测到设备事件后自动广播。 */
    EVENT(true),
    /** ICM V2 检测到告警后自动广播。 */
    ALERT(true);

    private final boolean provesAutomaticWakeup;

    IcmTransmissionTrigger(boolean provesAutomaticWakeup) {
        this.provesAutomaticWakeup = provesAutomaticWakeup;
    }

    /** 是否能作为“设备自动唤醒功能工作过”的机器证据。 */
    public boolean provesAutomaticWakeup() {
        return provesAutomaticWakeup;
    }
}
