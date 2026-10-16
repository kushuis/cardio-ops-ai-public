package io.cardioops.application.agentrun;

import io.cardioops.domain.agent.AgentRun;
import io.cardioops.domain.incident.IncidentType;

/**
 * 一种案件类型的处理器。分发器按 IncidentType 选一个。以后加别的调查，新增实现就行，公共 Worker 不用改。
 */
public interface AgentRunHandler {

    /** 当前 Handler 是否负责该案件类型。 */
    boolean supports(IncidentType incidentType);

    /**
     * 执行具体业务 Graph，并保存该业务的结构化结果。
     *
     * @param leaseGuard 业务边界必须主动检查它；丢失租约时停止本地执行，不能写失败状态
     */
    void handle(AgentRun run, AgentRunLeaseGuard leaseGuard);
}
