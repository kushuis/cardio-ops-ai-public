package io.cardioops.domain.customerservice;

import io.cardioops.domain.scheduledmiss.ScheduledMissHandlingRecommendation;

import java.time.Instant;
import java.util.Objects;

/**
 * 一次不能覆盖的人审记录。同一案件可以跟进多次，所以每次有自己的 reviewId。
 * 备注限制在 1 到 1000 字。调查建议如果本来就是人工复核，就没有“接受或拒绝模型的无需联系”这一栏。
 * 建议是无需联系时，accepted 必须和这次人工决定对得上。
 *
 *
 * 覆盖以后看不出中间改过口。评测和建议追溯都要整段历史。
 */
public record CustomerServiceReview(
        String reviewId,
        String incidentId,
        String runId,
        String operatorRef,
        CustomerServiceDecision decision,
        String note,
        ScheduledMissHandlingRecommendation aiRecommendation,
        Boolean acceptedAiRecommendation,
        long incidentVersionBefore,
        Instant createdAt
) {
    public CustomerServiceReview {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Objects.requireNonNull(incidentId, "incidentId must not be null");
        Objects.requireNonNull(operatorRef, "operatorRef must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(note, "note must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (reviewId.isBlank() || incidentId.isBlank() || operatorRef.isBlank()
                || (runId != null && runId.isBlank())) {
            throw new IllegalArgumentException("customer service review identity fields must not be blank");
        }
        if (note.isBlank() || note.length() > 1000) {
            throw new IllegalArgumentException("review note must contain 1 to 1000 characters");
        }
        if (incidentVersionBefore < 0) {
            throw new IllegalArgumentException("incidentVersionBefore must not be negative");
        }
        if (aiRecommendation == null && acceptedAiRecommendation != null) {
            throw new IllegalArgumentException("low-battery review has no scheduled-miss accept-or-reject label");
        }
        if (aiRecommendation == ScheduledMissHandlingRecommendation.HUMAN_REVIEW
                && acceptedAiRecommendation != null) {
            throw new IllegalArgumentException("human-review recommendation has no accept-or-reject label");
        }
        if (aiRecommendation == ScheduledMissHandlingRecommendation.NO_CONTACT_NEEDED) {
            boolean expectedAccepted = decision == CustomerServiceDecision.NO_CONTACT_CONFIRMED;
            if (acceptedAiRecommendation == null || acceptedAiRecommendation != expectedAccepted) {
                throw new IllegalArgumentException("AI feedback label must match the human decision");
            }
        }
    }
}
