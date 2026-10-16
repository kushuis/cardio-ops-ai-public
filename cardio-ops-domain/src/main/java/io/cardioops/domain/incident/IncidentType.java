package io.cardioops.domain.incident;

/**
 * 要持续跟进的问题分类，不是每一条原始信号。
 * 网关离线通常只是应传未传的一条证据。低电量会引出换机这一类独立工作，所以单独成案。
 */
public enum IncidentType {
    /** 到达 {@code icm_device_settings.deviceTime} 对应窗口后，网关定时传输仍未完成。 */
    SCHEDULED_GATEWAY_TRANSMISSION_MISSED,
    /** 设备电量低，需要客服独立确认传输能力、患者风险和后续换机安排。 */
    DEVICE_LOW_BATTERY,
    /** 设备报告的事件总数大于云端实收数，本次传输只有部分事件成功到达。 */
    TRANSMISSION_EVENTS_INCOMPLETE
}
