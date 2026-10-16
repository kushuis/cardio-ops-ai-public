package io.cardioops.agent.lowbattery;

/** 模型草稿与受治理工具实际记录的证据分开携带。 */
public record LowBatteryAgentOutput(
        /** 大模型生成的可解释性草稿。 */
        LowBatteryModelDraft draft,
        /** 本次受治理工具真正返回的数据，Validator 只信这一份。 */
        CollectedLowBatteryEvidence evidence
) { }
