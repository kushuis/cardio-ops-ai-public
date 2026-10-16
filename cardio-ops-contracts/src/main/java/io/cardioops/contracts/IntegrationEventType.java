package io.cardioops.contracts;

/**
 * Device Platform 发布给 CardioOps 的业务异常类型。原始 MQTT、完整心电图和设备命令不进入该 Topic。
 */
public enum IntegrationEventType {
    /**
     * 问题发现
     * DevicePlatform 的确定性规则发现需要调查的问题，例如应传窗口已过但没有成功传输。
     * 后续用于自动创建调查入口；CardioOps 不再重复计算“是否漏传”。
     */
    ISSUE_DETECTED
}
