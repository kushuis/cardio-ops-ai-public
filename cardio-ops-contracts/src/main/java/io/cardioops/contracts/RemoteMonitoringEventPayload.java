package io.cardioops.contracts;

/**
 * 每种远程监测 payload 至少要能拿出设备引用和源记录引用。没有这两个，后面的幂等和关联都没处挂。
 */
public sealed interface RemoteMonitoringEventPayload
        permits IssueDetectedPayload {

    /** 所有事件都必须能关联到一个脱敏设备引用。 */
    String deviceRef();

    /** 所有事件都必须指出自己的上游业务事实，供业务幂等和追溯使用。 */
    String sourceRecordRef();

    /** 所有事件都保存发生时的设备身份快照，避免固件升级后错误套用当前 Profile。 */
    DeviceIdentityPayload device();
}
