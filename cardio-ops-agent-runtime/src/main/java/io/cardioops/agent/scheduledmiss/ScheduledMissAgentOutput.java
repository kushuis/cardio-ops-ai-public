package io.cardioops.agent.scheduledmiss;

import java.util.Objects;

/**
 * 模型交回来的草稿，加上这次账本里的强类型证据。recovered 不在这里。
 */
public record ScheduledMissAgentOutput(
        ScheduledMissModelDraft draft,
        CollectedScheduledMissEvidence evidence
) {
    public ScheduledMissAgentOutput {
        Objects.requireNonNull(draft, "draft must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
    }
}
