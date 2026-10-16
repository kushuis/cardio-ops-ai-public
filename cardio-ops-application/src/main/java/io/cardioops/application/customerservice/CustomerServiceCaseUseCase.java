package io.cardioops.application.customerservice;

import java.util.Optional;

/**
 * 运营端查案件、提交复核、决定关闭建议的入口。不调模型，也不发联系动作。
 */
public interface CustomerServiceCaseUseCase {
    CustomerServiceCasePage list(CustomerServiceCaseQuery query);

    Optional<CustomerServiceCaseDetail> findById(String incidentId);

    CustomerServiceCaseDetail submitReview(SubmitCustomerServiceReviewCommand command);

    CustomerServiceCaseDetail decideClosureProposal(DecideClosureProposalCommand command);
}
