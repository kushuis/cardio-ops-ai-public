package io.cardioops.application.lowbattery;

import io.cardioops.domain.lowbattery.LowBatteryInvestigationResult;

/** 原子保存低电量结果、完成 Run，并把案件交给客服人工处理。 */
public interface LowBatteryInvestigationCompletionUseCase {
    void complete(String runId, String leaseToken, LowBatteryInvestigationResult result);
}
