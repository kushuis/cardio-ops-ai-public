package io.cardioops.domain.scheduledmiss;

import io.cardioops.domain.knowledge.KnowledgeCitation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 一次调查的最终结果。recovered 必须有“符合当前根因的恢复传输”或“应传前已改期”支撑。
 * 建议无需联系时，recovered 必须为真。模型摘要不能把这两个字段改口。
 *
 *
 * 前面就算有人绕过校验拼了一个对象，没有硬规则支撑的 recovered 也进不了系统。
 */
public record ScheduledMissInvestigationResult(
        String runId,
        String incidentId,
        boolean automaticWakeupObserved,
        boolean recoveryTransmissionObserved,
        boolean recovered,
        boolean withinGracePeriod,
        boolean scheduleChangedBeforeExpected,
        ScheduledMissHandlingRecommendation recommendation,
        List<String> evidenceRefs,
        List<ScheduledMissFact> facts,
        List<ScheduledMissHypothesis> hypotheses,
        List<KnowledgeCitation> knowledgeCitations,
        List<String> missingEvidence,
        String summary,
        Instant completedAt
) {
    public ScheduledMissInvestigationResult {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(incidentId, "incidentId must not be null");
        Objects.requireNonNull(recommendation, "recommendation must not be null");
        evidenceRefs = List.copyOf(evidenceRefs);
        facts = List.copyOf(facts);
        hypotheses = List.copyOf(hypotheses);
        knowledgeCitations = List.copyOf(knowledgeCitations == null ? List.of() : knowledgeCitations);
        missingEvidence = List.copyOf(missingEvidence);
        Objects.requireNonNull(summary, "summary must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        if (recovered != (recoveryTransmissionObserved || scheduleChangedBeforeExpected)) {
            throw new IllegalArgumentException("recovered must be backed by an approved hard rule");
        }
        if (recommendation == ScheduledMissHandlingRecommendation.NO_CONTACT_NEEDED && !recovered) {
            throw new IllegalArgumentException("no-contact recommendation requires recovery evidence");
        }
    }
}
