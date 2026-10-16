package io.cardioops.domain.scheduledmiss;

/**
 * 应传未传允许讨论的三种假设：低电量可能影响唤醒、云端和设备日程可能不一致、设备没到或没醒。
 * 这是调查假设，不是诊断。新的原因要业务确认后再加。
 */
public enum ScheduledMissHypothesisCode {
    /** 最近已知低电量可能影响设备自动激活，但历史电量不能冒充应传时刻的实时电量。 */
    LOW_BATTERY_MAY_PREVENT_WAKEUP,
    /** 程控仪或远程写入可能导致设备时间与云端预估时间不同步。 */
    DEVICE_AND_CLOUD_SCHEDULE_MAY_DIFFER,
    /** 当前遥测无法区分患者不在家、距离过远和设备未自动唤醒。 */
    DEVICE_NOT_REACHED_OR_NOT_WOKEN
}
