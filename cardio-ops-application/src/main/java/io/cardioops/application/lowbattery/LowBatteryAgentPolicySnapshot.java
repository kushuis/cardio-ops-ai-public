package io.cardioops.application.lowbattery;

import java.time.Instant;
import java.util.List;

/** 低电量 Run 首次执行前冻结的 Profile、Prompt 和工具合同。 */
public record LowBatteryAgentPolicySnapshot(
        /** 本次执行 ID，也是策略快照主键。 */
        String runId,
        /** 本次运行冻结的设备能力档案。 */
        String profileId,
        /** 整份设备档案配置版本。 */
        String deviceProfileVersion,
        /** 本次真正使用的模型名。 */
        String modelName,
        /** 低电量 Prompt 版本，便于回放与评测。 */
        String promptVersion,
        /** 工具授权策略版本。 */
        String toolPolicyVersion,
        /** 冻结后的三个可调用工具名。 */
        List<String> allowedToolNames,
        /** 首次执行创建快照的时间。 */
        Instant createdAt
) { }
