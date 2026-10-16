package io.cardioops.agent.scheduledmiss;

import io.cardioops.domain.scheduledmiss.ScheduledMissHypothesis;

import java.util.List;
import java.util.Objects;

/**
 * 模型的解释草稿。故意没有 recovered，也没有“要不要联系”。这两位由校验用证据重算，模型写在摘要里也不算数。
 */
public record ScheduledMissModelDraft(
        //假设
        List<ScheduledMissHypothesis> hypotheses,
        //知识引用
        List<String> knowledgeRefs,
        //缺失证据
        List<String> missingEvidence,
        //调查摘要
        String summary
) {
    public ScheduledMissModelDraft {
        hypotheses = List.copyOf(hypotheses == null ? List.of() : hypotheses);
        knowledgeRefs = List.copyOf(knowledgeRefs == null ? List.of() : knowledgeRefs);
        missingEvidence = List.copyOf(missingEvidence == null ? List.of() : missingEvidence);
        Objects.requireNonNull(summary, "summary must not be null");
        if (summary.isBlank()) {
            throw new IllegalArgumentException("model summary must not be blank");
        }
    }
}
