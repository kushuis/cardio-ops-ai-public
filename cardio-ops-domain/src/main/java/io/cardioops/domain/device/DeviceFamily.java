package io.cardioops.domain.device;

/**
 * 领域里的设备族。和跨系统消息枚举分开，领域层不依赖那份 JSON 协议。
 */
public enum DeviceFamily {
    /** 植入式心脏监测器。 */
    ICM,
    /** 植入式心律转复除颤器，能力和写操作风险通常高于 ICM。 */
    ICD
}
