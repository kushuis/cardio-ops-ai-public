package io.cardioops.domain.device;

/**
 * 档案解析的三种结果：唯一命中、没有命中、多份打平。后面能装配哪些工具，先看这个状态。
 */
public enum ProfileResolutionStatus {
    /** 唯一匹配到有效 Profile，可以使用该 Profile 明确授权的能力。 */
    SUPPORTED,
    /** 没有 Profile 覆盖此设备/固件/生效时间，只能进入安全只读降级。 */
    UNSUPPORTED_PROFILE,
    /** 有多个同等优先且同等具体的 Profile，配置存在歧义，禁止系统擅自选择。 */
    AMBIGUOUS_PROFILE
}
