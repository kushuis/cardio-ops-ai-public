package io.cardioops.application.customerservice;

import io.cardioops.domain.customerservice.CustomerServiceDecision;

import java.util.Objects;

/**
 * 客服提交一次人工结论。expectedVersion 是页面上看到的版本，对不上就冲突，避免盖掉别人刚写的决定。
 */
public record SubmitCustomerServiceReviewCommand(
        String incidentId,
        long expectedVersion,
        String operatorRef,
        CustomerServiceDecision decision,
        String note
) {
    public SubmitCustomerServiceReviewCommand {
        Objects.requireNonNull(incidentId, "incidentId must not be null");
        Objects.requireNonNull(operatorRef, "operatorRef must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(note, "note must not be null");
        incidentId = incidentId.trim();
        operatorRef = operatorRef.trim();
        note = note.trim();
        if (incidentId.isBlank() || operatorRef.isBlank()) {
            throw new IllegalArgumentException("incidentId and operatorRef must not be blank");
        }
        if (operatorRef.length() > 128) {
            throw new IllegalArgumentException("operatorRef must not exceed 128 characters");
        }
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
    }
}
