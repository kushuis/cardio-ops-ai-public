package io.cardioops.contracts;

/**
 * DevicePlatform 会主动发过来的三类异常。正常传输不进 CardioOps Topic。
 */
public enum PatientServiceIssueType {
    /** ICM/ICD 的 SCHEDULED_MISSED(2)：应传窗口已经过去但没有完成定时传输。 */
    SCHEDULED_MISSED,
    /** ICM/ICD 的 LOW_BATTERY(4)：需要单独建立客服跟进。 */
    LOW_BATTERY,
    /** 传输结束后，云端收到的 ECG 事件数少于设备声明的总数。 */
    TRANSMISSION_EVENTS_INCOMPLETE
}
