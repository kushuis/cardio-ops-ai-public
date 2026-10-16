package io.cardioops.application.scheduledmiss;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 应传未传复查计划的持久化端口；低电量和事件不全不使用这张计划表。 */
public interface ScheduledMissRecheckStore {

    /** 新案件创建计划；同一设备的新漏传并入旧案件时，用最新周期刷新计划。 */
    void initializeOrRefresh(
            String incidentId,
            Duration interval,
            Instant firstRecheckAt,
            boolean newlyCreated,
            Instant updatedAt
    );

    /** 在事务中锁住已到期计划，多 Worker 通过 SKIP LOCKED 分摊，不能重复排同一次复查。 */
    List<ScheduledMissRecheckPlan> lockDuePlans(Instant now, int limit);

    /** 成功排入一条新 AgentRun 后，增加复查次数并推进到下一个设备周期。 */
    void markQueued(ScheduledMissRecheckPlan plan, Instant updatedAt);

    Optional<ScheduledMissRecheckPlan> findByIncidentId(String incidentId);

    /** 发现恢复证据或达到三次上限后停止自动复查。 */
    void stop(String incidentId, Instant updatedAt);
}
