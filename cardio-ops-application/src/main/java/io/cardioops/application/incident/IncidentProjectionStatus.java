package io.cardioops.application.incident;

/**
 * 案件投影的分支。用它区分首次建案、同窗口幂等复用和缺少关联键。
 */
public enum IncidentProjectionStatus {
    /** 本次事实首次建立了 Incident；是否排队 AgentRun 以返回结果为准。 */
    INCIDENT_CREATED,
    /** 同一 incident_key 已经建案，复用旧案且不重复排队 AgentRun。 */
    INCIDENT_ALREADY_EXISTS,
    /** 命令缺少可靠关联键，不能按时间猜测后继续投影。 */
    UNCORRELATED_EVENT
}
