package io.cardioops.domain.lowbattery;

import java.time.Instant;
import java.util.List;

/**
 * 低电量场景最终结果。impactAssessment 由 Java Validator 根据传输证据重算，模型不能直接决定。
 */
public record LowBatteryInvestigationResult(
        /** 产生本结果的 AgentRun，便于回查模型、工具和 Checkpoint。 */
        String runId,
        /** 对应的低电量客服案件。 */
        String incidentId,
        /** Java Validator 根据传输事实重算的影响口径。 */
        LowBatteryImpactAssessment impactAssessment,
        /** 调查时当前网关是否在线；只作竞争原因参考。 */
        boolean gatewayAvailable,
        /** 模型从工具证据中归纳的已观察事实。 */
        List<String> observations,
        /** 仍需人工判断的可能解释，不等同于结论。 */
        List<String> hypotheses,
        /** 与某个假设相冲突的证据。 */
        List<String> contradictingEvidence,
        /** 当前无法从 DevicePlatform 获取、因此不能下结论的信息。 */
        List<String> missingEvidence,
        /** 经 Validator 白名单过滤后保留的真实证据引用。 */
        List<String> evidenceRefs,
        /** 展示给客服的调查摘要。 */
        String summary,
        /** Validator 完成时间。 */
        Instant completedAt
) { }
