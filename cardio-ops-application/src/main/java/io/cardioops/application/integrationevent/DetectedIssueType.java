package io.cardioops.application.integrationevent;

/**
 * 应用层认的问题类型。由边界上的解码从契约显式翻译过来，不在业务代码里读原始字符串。
 */
public enum DetectedIssueType {
    /** 上游已判定到达应传窗口但未完成定时网关传输。 */
    SCHEDULED_MISSED,
    /** 上游已判定设备低电量，需要独立客服跟进。 */
    LOW_BATTERY,
    /** 上游已判定一次传输的 ECG 事件没有全部到达。 */
    TRANSMISSION_EVENTS_INCOMPLETE
}
