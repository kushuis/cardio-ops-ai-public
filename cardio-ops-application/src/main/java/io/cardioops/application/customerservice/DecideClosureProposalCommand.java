package io.cardioops.application.customerservice;

import java.util.Objects;

/**
 * 主管批一份关闭建议。批准人引用由已经校验过的 BFF 注入，不由浏览器自己报。提案人不能批自己的那一份。
 */
public record DecideClosureProposalCommand(
        String incidentId,
        String proposalId,
        long expectedVersion,
        String approverRef,
        boolean approved,
        String note
) {
    public DecideClosureProposalCommand {
        Objects.requireNonNull(incidentId, "incidentId must not be null");
        Objects.requireNonNull(proposalId, "proposalId must not be null");
        Objects.requireNonNull(approverRef, "approverRef must not be null");
        Objects.requireNonNull(note, "note must not be null");
        if (incidentId.isBlank() || proposalId.isBlank() || approverRef.isBlank()) {
            throw new IllegalArgumentException("closure decision identity fields must not be blank");
        }
        if (expectedVersion < 0 || note.isBlank() || note.length() > 1000) {
            throw new IllegalArgumentException("closure decision version or note is invalid");
        }
    }
}
