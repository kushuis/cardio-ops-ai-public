package io.cardioops.domain.scheduledmiss;

/**
 * 远程改广播时间的执行状态。还在排队、失败或过期，都不能当成已经改掉旧应传时刻。
 */
public enum ScheduleChangeStatus {
    /** 已提交但尚未写入设备，不能认为新时间已经生效。 */
    PENDING,
    /** 已写入并由既有业务流程确认成功，可以作为配置已变化的证据。 */
    SUCCEEDED,
    /** 写入失败，旧应传窗口仍不能据此作废。 */
    FAILED,
    /** 指令超过有效期，未能完成写入。 */
    EXPIRED
}
