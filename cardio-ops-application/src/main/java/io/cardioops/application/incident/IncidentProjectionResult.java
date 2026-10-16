package io.cardioops.application.incident;

/**
 * 一次投影做了什么：新建案件、幂等复用同窗口案件，或因缺少关联键而跳过。
 * 日志和测试靠这个区分分支。
 */
public record IncidentProjectionResult(
        IncidentProjectionStatus status,
        String incidentId,
        String evidenceId,
        boolean agentRunQueued
) {
}
