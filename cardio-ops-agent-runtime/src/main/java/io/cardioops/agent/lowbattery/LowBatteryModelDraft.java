package io.cardioops.agent.lowbattery;

import java.util.List;

/** 模型只生成解释草稿，不含最终 impactAssessment。 */
public record LowBatteryModelDraft(
        /** 工具返回后能够直接确认的事实描述。 */
        List<String> observations,
        /** 低电量、网关或其他链路原因等待人工判断的假设。 */
        List<String> hypotheses,
        /** 削弱上述假设的反向事实。 */
        List<String> contradictingEvidence,
        /** 当前证据源无法回答的问题。 */
        List<String> missingEvidence,
        /** 模型声称引用的证据；Validator 会过滤不存在的引用。 */
        List<String> evidenceRefs,
        /** 给客服阅读的简短结论，不承担最终硬判定。 */
        String summary
) { }
