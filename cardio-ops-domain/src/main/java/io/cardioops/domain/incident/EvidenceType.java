package io.cardioops.domain.incident;

/**
 * 当前三条流程真正会产生的证据类型。还没确认的信号不先占一个枚举值。
 */
public enum EvidenceType {
    /** DevicePlatform 已确认到点未收到计划传输。 */
    SCHEDULED_MISSED,
    /** DevicePlatform 已确认设备低电量。 */
    LOW_BATTERY,
    /** DevicePlatform 已确认某次传输只收到部分 ECG 事件。 */
    TRANSMISSION_EVENTS_INCOMPLETE
}
