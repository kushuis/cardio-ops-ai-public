package io.cardioops.application.lowbattery;

import io.cardioops.application.agentrun.AgentToolCallAudit;
import io.cardioops.domain.lowbattery.LowBatteryInvestigationResult;

import java.util.Optional;

/** 低电量场景自己的策略快照、工具审计和最终结果。 */
public interface LowBatteryInvestigationStore {
    void savePolicySnapshotIfAbsent(LowBatteryAgentPolicySnapshot snapshot);
    Optional<LowBatteryAgentPolicySnapshot> findPolicySnapshot(String runId);
    void appendToolCall(AgentToolCallAudit audit);
    void saveResult(LowBatteryInvestigationResult result);
    Optional<LowBatteryInvestigationResult> findResult(String runId);
}
