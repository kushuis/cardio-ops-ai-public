package io.cardioops.domain.scheduledmiss;

/**
 * 某个明确时刻网关是未绑定、在线还是离线。没有时刻的“当前感觉”不能写进这个枚举。
 */
public enum GatewayConnectivity {
    /** 当时设备没有绑定可用于家庭监护的网关。 */
    UNBOUND,
    /** 当时网关在线；它不能单独证明植入设备位于蓝牙范围内。 */
    ONLINE,
    /** 当时网关离线，无法承接植入设备的蓝牙广播。 */
    OFFLINE
}
