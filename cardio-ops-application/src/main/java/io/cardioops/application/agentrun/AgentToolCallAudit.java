package io.cardioops.application.agentrun;

import java.time.Instant;
import java.util.Objects;

/**
 * 一次工具调用的审计。记的是摘要、条数、是否截断和错误码，不把原始查询词写进去。
 */
public record AgentToolCallAudit(
        String auditId,
        String runId,
        String incidentId,
        String toolName,
        AgentToolCallStatus status,
        String argumentDigest,
        int resultItemCount,
        boolean truncated,
        String errorCode,
        Instant startedAt,
        Instant completedAt
) {
    public AgentToolCallAudit {
        Objects.requireNonNull(auditId, "auditId must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(incidentId, "incidentId must not be null");
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(argumentDigest, "argumentDigest must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        if (resultItemCount < 0) {
            throw new IllegalArgumentException("resultItemCount must not be negative");
        }
    }
}
