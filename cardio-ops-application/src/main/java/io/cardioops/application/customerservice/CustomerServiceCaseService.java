package io.cardioops.application.customerservice;

import io.cardioops.domain.customerservice.CustomerServiceDecision;
import io.cardioops.domain.customerservice.CustomerServiceClosureProposal;
import io.cardioops.domain.customerservice.CustomerServiceReview;
import io.cardioops.domain.customerservice.ClosureProposalStatus;
import io.cardioops.domain.incident.IncidentStatus;
import io.cardioops.domain.incident.IncidentType;
import io.cardioops.domain.lowbattery.LowBatteryInvestigationResult;
import io.cardioops.domain.scheduledmiss.ScheduledMissHandlingRecommendation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 人已经开始处理案件之后的确定性编排。不调模型，不打电话，不发短信，也不写设备。
 *
 * <p>调查图在这之前就已经走到 END 了。HUMAN_REVIEW 或无需联系，都只是结果里的建议。
 * 人工批准发生在这个服务里，不是图上的中断，也没有 Checkpoint 在这里等人点头。
 * 确认无需联系时，这里只留下一份待审批的关闭建议。案件要等另一个人批准才关闭，
 * 提建议的人不能批自己的那一份。</p>
 *
 * <p>
 * 图负责查完并给出建议。人要看的是已经落下来的结果，版本和证据还要在批准时再核一遍。
 * 把人等在图里面，崩溃续跑和等人批准就变成了同一种 resume。</p>
 */
public final class CustomerServiceCaseService implements CustomerServiceCaseUseCase {

    private static final Logger log = LoggerFactory.getLogger(CustomerServiceCaseService.class);
    private final CustomerServiceCaseStore store;
    private final Clock clock;

    public CustomerServiceCaseService(CustomerServiceCaseStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public CustomerServiceCasePage list(CustomerServiceCaseQuery query) {
        return store.list(query);
    }

    @Override
    public Optional<CustomerServiceCaseDetail> findById(String incidentId) {
        return store.findById(incidentId);
    }

    /**
     * 只接受还在等人看的案件：待复核，或者已经要求继续跟进。版本对不上就冲突，不覆盖别人刚写的决定。
     *
     * <p>应传未传仍以 Java 重算的 recovered 和建议为准；低电量、传输事件不全属于人工场景，
     * 不借用应传未传的恢复条件。三类案件确认无需联系后都只生成待审批建议，不直接关案；
     * 其他决定把案件转到继续跟进。</p>
     *
     * <p>
     * 确认的人就是提案人。关闭还要另一个人看过证据。这一步只把建议留下。</p>
     */
    @Override
    public CustomerServiceCaseDetail submitReview(SubmitCustomerServiceReviewCommand command) {
        CustomerServiceCaseDetail current = store.findById(command.incidentId())
                .orElseThrow(() -> new CustomerServiceCaseNotFoundException(command.incidentId()));
        IncidentStatus currentStatus = current.summary().status();
        if (currentStatus != IncidentStatus.PENDING_HUMAN_REVIEW
                && currentStatus != IncidentStatus.FOLLOW_UP_REQUIRED) {
            throw new CustomerServiceCaseConflictException(command.incidentId());
        }
        if (current.summary().version() != command.expectedVersion()) {
            throw new CustomerServiceCaseConflictException(command.incidentId());
        }
        boolean scheduledMiss = current.summary().incidentType()
                == IncidentType.SCHEDULED_GATEWAY_TRANSMISSION_MISSED;
        if (scheduledMiss && current.investigation() == null) {
            throw new CustomerServiceCaseConflictException(command.incidentId());
        }

        Instant now = clock.instant();
        boolean saved;
        if (scheduledMiss) {
            saved = submitScheduledMissReview(current, command, now);
        } else {
            saved = submitManualIncidentReview(current, command, now);
        }
        if (!saved) {
            log.warn("customer service review conflicted incidentId={} operatorRef={} expectedVersion={}",
                    command.incidentId(), command.operatorRef(), command.expectedVersion());
            throw new CustomerServiceCaseConflictException(command.incidentId());
        }
        log.info("customer service review committed incidentId={} operatorRef={} decision={}",
                command.incidentId(), command.operatorRef(), command.decision());
        return store.findById(command.incidentId())
                .orElseThrow(() -> new CustomerServiceCaseNotFoundException(command.incidentId()));
    }

    /**
     * 主管决定一份还挂着的关闭建议。案件得在待批准，版本得对上，批准人不能是提案人。
     *
     * <p>拒绝就回到继续跟进。应传未传在批准前重验 recovered、建议和证据引用；
     * 低电量有调查结果时重验 Run 与证据引用；纯人工案件以双人审批记录作为关单依据。</p>
     *
     * <p>
     * 提案和批准之间结果可能变了。按当时的快照关掉，会把已经不成立的恢复写成结案。</p>
     */
    @Override
    public CustomerServiceCaseDetail decideClosureProposal(DecideClosureProposalCommand command) {
        CustomerServiceCaseDetail current = store.findById(command.incidentId())
                .orElseThrow(() -> new CustomerServiceCaseNotFoundException(command.incidentId()));
        CustomerServiceClosureProposal proposal = current.closureProposals().stream()
                .filter(item -> item.proposalId().equals(command.proposalId()))
                .findFirst()
                .orElseThrow(() -> new CustomerServiceCaseNotFoundException(command.incidentId()));

        // 同一份决定重放时，建议已经不在 PENDING。决定一样就返回现状，不再改案件。
        if (proposal.status() != ClosureProposalStatus.PENDING) {
            boolean sameDecision = (command.approved() && proposal.status() != ClosureProposalStatus.REJECTED)
                    || (!command.approved() && proposal.status() == ClosureProposalStatus.REJECTED);
            if (sameDecision) {
                return current;
            }
            throw new CustomerServiceCaseConflictException(command.incidentId());
        }
        boolean scheduledMiss = current.summary().incidentType()
                == IncidentType.SCHEDULED_GATEWAY_TRANSMISSION_MISSED;
        boolean lowBattery = current.summary().incidentType() == IncidentType.DEVICE_LOW_BATTERY;
        if (current.summary().status() != IncidentStatus.PENDING_CLOSURE_APPROVAL
                || current.summary().version() != command.expectedVersion()
                || proposal.proposedBy().equals(command.approverRef())
                || (scheduledMiss && current.investigation() == null)) {
            throw new CustomerServiceCaseConflictException(command.incidentId());
        }

        boolean evidenceStillValid = scheduledMiss
                ? current.investigation().runId().equals(proposal.runId())
                && current.investigation().recovered()
                && current.investigation().recommendation() == ScheduledMissHandlingRecommendation.NO_CONTACT_NEEDED
                && current.investigation().evidenceRefs().containsAll(proposal.evidenceRefs())
                : manualIncidentEvidenceStillValid(current.lowBatteryInvestigation(), proposal);
        ClosureProposalStatus proposalStatus;
        IncidentStatus targetStatus;
        String verificationCode;
        if (!command.approved()) {
            proposalStatus = ClosureProposalStatus.REJECTED;
            targetStatus = IncidentStatus.FOLLOW_UP_REQUIRED;
            verificationCode = "REJECTED_BY_SUPERVISOR";
        } else if (!evidenceStillValid) {
            proposalStatus = ClosureProposalStatus.VERIFICATION_FAILED;
            targetStatus = IncidentStatus.PENDING_HUMAN_REVIEW;
            verificationCode = scheduledMiss ? "RECOVERY_EVIDENCE_CHANGED"
                    : lowBattery ? "LOW_BATTERY_EVIDENCE_CHANGED" : "MANUAL_EVIDENCE_CHANGED";
        } else {
            proposalStatus = ClosureProposalStatus.APPROVED;
            targetStatus = IncidentStatus.CLOSED;
            verificationCode = scheduledMiss ? "RECOVERY_EVIDENCE_VERIFIED"
                    : lowBattery ? "LOW_BATTERY_HUMAN_CONFIRMED" : "MANUAL_REVIEW_CONFIRMED";
        }

        Instant now = clock.instant();
        log.info("closure proposal deciding incidentId={} proposalId={} approverRef={} approved={} "
                        + "evidenceStillValid={} targetStatus={}",
                command.incidentId(), command.proposalId(), command.approverRef(), command.approved(),
                evidenceStillValid, targetStatus);
        boolean saved = store.decideClosureProposal(
                command.proposalId(), command.incidentId(), command.expectedVersion(), proposalStatus,
                command.approverRef(), command.note(), verificationCode, targetStatus, now,
                targetStatus == IncidentStatus.CLOSED ? now : null);
        if (!saved) {
            throw new CustomerServiceCaseConflictException(command.incidentId());
        }
        return store.findById(command.incidentId())
                .orElseThrow(() -> new CustomerServiceCaseNotFoundException(command.incidentId()));
    }

    /**
     * 低电量和传输事件不全不借用应传未传的恢复结论。低电量有 Java 结果就挂上 Run 和证据引用；
     * ICD、调查失败或传输事件不全没有结果时，人仍然可以复核，关单提案可以不带 Run。
     * 确认无需联系只是提交给另一个人批，这里不直接关案，也不联系患者。
     */
    private boolean submitManualIncidentReview(
            CustomerServiceCaseDetail current,
            SubmitCustomerServiceReviewCommand command,
            Instant now
    ) {
        LowBatteryInvestigationResult battery = current.lowBatteryInvestigation();
        String runId = battery == null ? null : battery.runId();
        var review = new CustomerServiceReview(
                UUID.randomUUID().toString(), command.incidentId(), runId,
                command.operatorRef(), command.decision(), command.note(), null, null,
                command.expectedVersion(), now);
        log.info("customer service review submitting incidentId={} runId={} operatorRef={} decision={} "
                        + "expectedVersion={}",
                command.incidentId(), runId, command.operatorRef(),
                command.decision(), command.expectedVersion());
        if (command.decision() != CustomerServiceDecision.NO_CONTACT_CONFIRMED) {
            return store.appendReviewAndTransition(
                    review, command.expectedVersion(), IncidentStatus.FOLLOW_UP_REQUIRED, now, null);
        }
        List<String> evidenceRefs = battery == null || battery.evidenceRefs() == null
                ? List.of() : battery.evidenceRefs();
        var proposal = new CustomerServiceClosureProposal(
                UUID.randomUUID().toString(), command.incidentId(), runId,
                command.operatorRef(), command.note(), evidenceRefs,
                ClosureProposalStatus.PENDING, command.expectedVersion(), now,
                null, null, null, null);
        return store.appendReviewAndCreateClosureProposal(
                review, proposal, command.expectedVersion(), now);
    }

    private boolean submitScheduledMissReview(
            CustomerServiceCaseDetail current,
            SubmitCustomerServiceReviewCommand command,
            Instant now
    ) {
        String runId = current.investigation().runId();
        ScheduledMissHandlingRecommendation recommendation = current.investigation().recommendation();
        Boolean accepted = recommendation == ScheduledMissHandlingRecommendation.NO_CONTACT_NEEDED
                ? command.decision() == CustomerServiceDecision.NO_CONTACT_CONFIRMED : null;
        var review = new CustomerServiceReview(
                UUID.randomUUID().toString(), command.incidentId(), runId,
                command.operatorRef(), command.decision(), command.note(), recommendation, accepted,
                command.expectedVersion(), now);
        log.info("customer service review submitting incidentId={} runId={} operatorRef={} decision={} "
                        + "expectedVersion={}",
                command.incidentId(), runId, command.operatorRef(),
                command.decision(), command.expectedVersion());
        if (command.decision() == CustomerServiceDecision.NO_CONTACT_CONFIRMED) {
            if (!current.investigation().recovered() || current.investigation().evidenceRefs().isEmpty()) {
                throw new IllegalArgumentException("CLOSURE_PROPOSAL_REQUIRES_RECOVERY_EVIDENCE");
            }
            var proposal = new CustomerServiceClosureProposal(
                    UUID.randomUUID().toString(), command.incidentId(), runId,
                    command.operatorRef(), command.note(), current.investigation().evidenceRefs(),
                    ClosureProposalStatus.PENDING, command.expectedVersion(), now,
                    null, null, null, null);
            return store.appendReviewAndCreateClosureProposal(
                    review, proposal, command.expectedVersion(), now);
        }
        return store.appendReviewAndTransition(
                review, command.expectedVersion(), IncidentStatus.FOLLOW_UP_REQUIRED, now, null);
    }

    /** 没有场景调查结果时，人工备注就是关单依据；低电量有结果时，Run 和证据引用必须还在。 */
    private static boolean manualIncidentEvidenceStillValid(
            LowBatteryInvestigationResult result,
            CustomerServiceClosureProposal proposal
    ) {
        if (result == null) {
            return true;
        }
        List<String> evidenceRefs = result.evidenceRefs() == null ? List.of() : result.evidenceRefs();
        return result.runId().equals(proposal.runId()) && evidenceRefs.containsAll(proposal.evidenceRefs());
    }
}
