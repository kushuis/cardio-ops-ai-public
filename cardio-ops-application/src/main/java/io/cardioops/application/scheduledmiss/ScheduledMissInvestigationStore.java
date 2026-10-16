package io.cardioops.application.scheduledmiss;

import io.cardioops.application.agentrun.AgentToolCallAudit;
import io.cardioops.domain.scheduledmiss.ScheduledMissInvestigationResult;

import java.util.Optional;

/**
 * 应传未传自己的策略快照、工具审计和结果。公共运行队列不放在这里。
 */
public interface ScheduledMissInvestigationStore {

    /** 首次执行时保存不可变策略快照；重试不得覆盖原快照。 */
    void savePolicySnapshotIfAbsent(ScheduledMissAgentPolicySnapshot snapshot);

    /** 恢复执行必须读取首次冻结的精确 Profile/Prompt/Tool/Knowledge 版本。 */
    Optional<ScheduledMissAgentPolicySnapshot> findPolicySnapshot(String runId);

    /** 追加不可变 Tool 调用审计。 */
    void appendToolCall(AgentToolCallAudit audit);

    /** 保存经过 Java 业务规则校验的应传未传调查结果。 */
    void saveResult(ScheduledMissInvestigationResult result);

    /** 供 API、测试和重放逻辑读取最终结果。 */
    Optional<ScheduledMissInvestigationResult> findResult(String runId);
}
