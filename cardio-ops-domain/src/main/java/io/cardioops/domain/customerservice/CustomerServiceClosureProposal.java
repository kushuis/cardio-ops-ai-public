package io.cardioops.domain.customerservice;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 客服提出的关闭建议。还没批的时候不能带上审批字段；批完了必须留下谁批的、什么时候、核验码。
 * 建议里引用的恢复证据，批准时要跟当前调查结果再对一遍，不能按提案当时的印象关案。
 *
 *
 * 批准人对的是这一份引用还在不在、结论变没变。不冻住，事后说不清当时凭什么提出关闭。
 */
public record CustomerServiceClosureProposal(
        String proposalId,
        String incidentId,
        String runId,
        String proposedBy,
        String reason,
        List<String> evidenceRefs,
        ClosureProposalStatus status,
        long incidentVersionBefore,
        Instant createdAt,
        String decidedBy,
        String decisionNote,
        Instant decidedAt,
        String verificationCode
) {
    public CustomerServiceClosureProposal {
        Objects.requireNonNull(proposalId, "proposalId must not be null");
        Objects.requireNonNull(incidentId, "incidentId must not be null");
        Objects.requireNonNull(proposedBy, "proposedBy must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(evidenceRefs, "evidenceRefs must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        evidenceRefs = List.copyOf(evidenceRefs);
        if (proposalId.isBlank() || incidentId.isBlank() || proposedBy.isBlank()
                || (runId != null && runId.isBlank())) {
            throw new IllegalArgumentException("closure proposal identity fields must not be blank");
        }
        if (reason.isBlank() || reason.length() > 1000) {
            throw new IllegalArgumentException("closure proposal requires a reason");
        }
        boolean pending = status == ClosureProposalStatus.PENDING;
        if (pending && (decidedBy != null || decisionNote != null || decidedAt != null || verificationCode != null)) {
            throw new IllegalArgumentException("pending proposal cannot contain decision fields");
        }
        if (!pending && (decidedBy == null || decisionNote == null || decidedAt == null)) {
            throw new IllegalArgumentException("completed proposal requires decision audit fields");
        }
    }
}
