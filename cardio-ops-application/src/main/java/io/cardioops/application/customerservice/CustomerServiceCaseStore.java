package io.cardioops.application.customerservice;

import io.cardioops.domain.customerservice.CustomerServiceReview;
import io.cardioops.domain.customerservice.CustomerServiceClosureProposal;
import io.cardioops.domain.customerservice.ClosureProposalStatus;
import io.cardioops.domain.incident.IncidentStatus;

import java.time.Instant;
import java.util.Optional;

/**
 * 案件查询和人审事务的端口。乐观锁冲突时返回 false，由用例抛冲突，不在这里覆盖别人的决定。
 */
public interface CustomerServiceCaseStore {

    /** 只返回已经生成调查结果、可以展示给客服的案件。 */
    CustomerServiceCasePage list(CustomerServiceCaseQuery query);

    /** 查询最新调查结果和完整人工历史。 */
    Optional<CustomerServiceCaseDetail> findById(String incidentId);

    /**
     * 原子追加人工记录并推进案件状态。
     *
     * @return 版本和活动状态均匹配时返回 true；并发更新或案件已关闭返回 false
     */
    boolean appendReviewAndTransition(
            CustomerServiceReview review,
            long expectedVersion,
            IncidentStatus targetStatus,
            Instant updatedAt,
            Instant resolvedAt
    );

    /** 原子保存客服结论和关单提案，并把案件推进到待主管审批。 */
    boolean appendReviewAndCreateClosureProposal(
            CustomerServiceReview review,
            CustomerServiceClosureProposal proposal,
            long expectedVersion,
            Instant updatedAt
    );

    /** 原子落主管决定与案件状态；CAS 失败返回 false。 */
    boolean decideClosureProposal(
            String proposalId,
            String incidentId,
            long expectedVersion,
            ClosureProposalStatus proposalStatus,
            String approverRef,
            String note,
            String verificationCode,
            IncidentStatus targetStatus,
            Instant decidedAt,
            Instant resolvedAt
    );
}
