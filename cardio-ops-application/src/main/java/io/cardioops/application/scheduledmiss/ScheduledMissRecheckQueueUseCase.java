package io.cardioops.application.scheduledmiss;

/** 将已到设备应传周期的复查计划转换成新的 AgentRun。 */
public interface ScheduledMissRecheckQueueUseCase {

    int queueDueRechecks(int limit);
}
