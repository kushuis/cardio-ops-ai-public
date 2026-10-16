package io.cardioops.application.customerservice;

import io.cardioops.domain.customerservice.CustomerServiceReview;
import io.cardioops.domain.customerservice.CustomerServiceClosureProposal;
import io.cardioops.domain.scheduledmiss.ScheduledMissInvestigationResult;
import io.cardioops.domain.lowbattery.LowBatteryInvestigationResult;

import java.util.List;
import java.util.Objects;

/**
 * 打开案件时看到的全貌：概要、调查结果、按时间排的全部人审，以及关闭建议。不能只留最后一次复核。
 * 没有跑过 Agent 的案件，调查结果为空，人审和关闭建议也还是空列表。
 */
public record CustomerServiceCaseDetail(
        CustomerServiceCaseSummary summary,
        ScheduledMissInvestigationResult investigation,
        LowBatteryInvestigationResult lowBatteryInvestigation,
        List<CustomerServiceReview> reviews,
        List<CustomerServiceClosureProposal> closureProposals
) {
    public CustomerServiceCaseDetail {
        Objects.requireNonNull(summary, "summary must not be null");
        reviews = List.copyOf(reviews);
        closureProposals = List.copyOf(closureProposals);
    }
}
