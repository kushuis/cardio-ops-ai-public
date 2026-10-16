package io.cardioops.domain.lowbattery;

/**
 * 低电量告警之后的传输影响评估。它描述“目前观察到什么”，不把相关性冒充为电池故障因果。
 */
public enum LowBatteryImpactAssessment {
    /** 告警后存在完整传输，当前没有观察到低电量影响最近传输。 */
    NO_OBSERVED_IMPACT,
    /** 告警后最近一次传输不完整，低电量可能影响传输，但仍需人工结合网关等竞争原因复核。 */
    POSSIBLE_IMPACT,
    /** 告警后没有足够的传输事实，不能判断是否产生影响。 */
    INSUFFICIENT_EVIDENCE;

    /** 客服列表使用的 Java 结论，不采用模型摘要。 */
    public String listText() {
        return switch (this) {
            case NO_OBSERVED_IMPACT -> "低电量后最新传输完整，当前没有观察到影响。";
            case POSSIBLE_IMPACT -> "低电量后最新传输不完整，可能有关，需要人工复核。";
            case INSUFFICIENT_EVIDENCE -> "低电量后没有足够的传输证据。";
        };
    }
}
