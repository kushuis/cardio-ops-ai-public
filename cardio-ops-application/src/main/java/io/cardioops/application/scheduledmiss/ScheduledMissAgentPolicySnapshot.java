package io.cardioops.application.scheduledmiss;

import io.cardioops.domain.scheduledmiss.IcmProtocolGeneration;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 调查开始前冻住的边界：档案、协议代际、允许的工具、提示词和知识修订。恢复时对的是这份，不是目录里现在的最新版。
 */
public record ScheduledMissAgentPolicySnapshot(
        String runId,
        String profileId,
        String deviceProfileVersion,
        IcmProtocolGeneration protocolGeneration,
        String modelName,
        String promptVersion,
        String toolPolicyVersion,
        List<String> allowedToolNames,
        List<String> knowledgeRevisionKeys,
        Instant createdAt
) {
    public ScheduledMissAgentPolicySnapshot {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(deviceProfileVersion, "deviceProfileVersion must not be null");
        Objects.requireNonNull(protocolGeneration, "protocolGeneration must not be null");
        Objects.requireNonNull(modelName, "modelName must not be null");
        Objects.requireNonNull(promptVersion, "promptVersion must not be null");
        Objects.requireNonNull(toolPolicyVersion, "toolPolicyVersion must not be null");
        allowedToolNames = List.copyOf(allowedToolNames);
        knowledgeRevisionKeys = List.copyOf(knowledgeRevisionKeys);
        if (knowledgeRevisionKeys.isEmpty()) {
            throw new IllegalArgumentException("knowledgeRevisionKeys must not be empty");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
