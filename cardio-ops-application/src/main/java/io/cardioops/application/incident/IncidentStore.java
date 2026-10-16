package io.cardioops.application.incident;

import io.cardioops.domain.agent.AgentRun;
import io.cardioops.domain.incident.Incident;
import io.cardioops.domain.incident.IncidentEvidence;

import java.time.Instant;

/**
 * 案件写端口。用例说要原子地插入或挂上证据。唯一键冲突怎么处理，留在 JDBC。
 */
public interface IncidentStore {

    /** 并发安全地创建案件；incident_key 已存在时返回既有案件。 */
    IncidentUpsertResult createOrFind(Incident candidate);

    /** evidence_key 幂等写入；重复证据返回 false。 */
    boolean saveEvidenceIfAbsent(IncidentEvidence evidence);

    /** 只允许 lastEvidenceAt 向未来推进，避免乱序消息破坏时间线。 */
    void advanceLastEvidenceAt(String incidentId, Instant evidenceAt);

    /** 每个案件同时最多一个活动 Run；唯一约束负责并发兜底。 */
    boolean queueRunIfNoActive(AgentRun run);

    /**
     * 恢复时进入客服确认关单；三次复查未恢复时转人工跟进。
     * 未恢复且尚有复查次数时保持 DETECTED，不提前占用客服。
     */
    void recordScheduledMissInvestigationOutcome(
            String incidentId,
            boolean recovered,
            boolean rechecksExhausted,
            Instant updatedAt
    );

    /** 低电量自动调查只补充客服证据，完成后始终进入待人工处理，不自动关案。 */
    void recordLowBatteryInvestigationCompleted(String incidentId, Instant updatedAt);
}
