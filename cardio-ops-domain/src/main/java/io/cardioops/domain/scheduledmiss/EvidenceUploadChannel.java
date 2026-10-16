package io.cardioops.domain.scheduledmiss;

/**
 * 证据从哪条通道上来：网关还是小程序。通道和设备为什么触发是两件事，不能互相代替。
 */
public enum EvidenceUploadChannel {
    /** 家庭蓝牙网关接收设备广播后上传云端。 */
    GATEWAY,
    /** 患者在微信小程序中主动连接并上传。 */
    MINI_PROGRAM
}
