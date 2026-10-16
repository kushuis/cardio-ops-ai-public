package io.cardioops.contracts;

/**
 * 契约里露出的设备族。领域层有自己的枚举，不直接依赖这份消息协议。
 */
public enum DeviceFamilyCode {
    /** 植入式心脏监测器，主要持续记录并上传心电事件。 */
    ICM,
    /** 植入式心律转复除颤器，除监测外还涉及更严格的治疗参数与设备安全边界。 */
    ICD
}
