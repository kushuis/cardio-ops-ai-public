package io.cardioops.application.scheduledmiss;

import io.cardioops.domain.scheduledmiss.ScheduledMissInvestigationResult;

/**
 * 一次调查收尾必须在同一个本地事务里保存结果、完成运行，并把案件推进到待人看。不能自动关案。
 */
public interface ScheduledMissInvestigationCompletionUseCase {

    /** 原子保存调查结果，并把对应公共 Run 标记为 COMPLETED。 */
    void complete(String runId, String leaseToken, ScheduledMissInvestigationResult result);
}
