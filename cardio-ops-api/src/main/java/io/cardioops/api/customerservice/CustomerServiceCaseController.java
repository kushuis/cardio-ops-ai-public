package io.cardioops.api.customerservice;

import io.cardioops.application.customerservice.CustomerServiceCaseConflictException;
import io.cardioops.application.customerservice.CustomerServiceCaseDetail;
import io.cardioops.application.customerservice.CustomerServiceCaseNotFoundException;
import io.cardioops.application.customerservice.CustomerServiceCasePage;
import io.cardioops.application.customerservice.CustomerServiceCaseQuery;
import io.cardioops.application.customerservice.CustomerServiceCaseUseCase;
import io.cardioops.application.customerservice.SubmitCustomerServiceReviewCommand;
import io.cardioops.application.customerservice.DecideClosureProposalCommand;
import io.cardioops.domain.customerservice.CustomerServiceDecision;
import io.cardioops.domain.incident.IncidentStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

/**
 * BFF 用的内部案件接口。和持久化共用 integration.enabled。不用 ConditionalOnBean，因为控制器扫描和 @Bean 方法的顺序不稳定，会出现用例其实在、接口却 404。
 * 复核和批准各开一段 Span：提出关闭建议、普通复核、批准决定。要不要导出看 management.opentelemetry.enabled，CARDIO_OPS_OTEL_ENABLED，默认关。
 * 版本冲突返回 409，让页面刷新。这里不调模型，也不把批准做成图的中断。
 *
 *
 * 批准操作使用独立凭证，并强制批准人与提案人分离；独立入口便于实施差异化访问控制。
 */

@RestController
@RequestMapping("/internal/v1/customer-service/cases")

@ConditionalOnProperty(prefix = "cardio-ops.integration", name = "enabled", havingValue = "true")
public class CustomerServiceCaseController {

    private static final Logger log = LoggerFactory.getLogger(CustomerServiceCaseController.class);
    private final CustomerServiceCaseUseCase useCase;
    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;

    public CustomerServiceCaseController(
            CustomerServiceCaseUseCase useCase,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry
    ) {
        this.useCase = useCase;
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
    }

    @GetMapping
    public CustomerServiceCasePage list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) IncidentStatus status,
            @RequestParam(required = false) String deviceRef
    ) {
        log.info("customer service cases queried page={} size={} status={} hasDeviceFilter={}",
                page, size, status, deviceRef != null && !deviceRef.isBlank());
        return useCase.list(new CustomerServiceCaseQuery(page, size, status, deviceRef));
    }

    @GetMapping("/{incidentId}")
    public CustomerServiceCaseDetail detail(@PathVariable String incidentId) {
        return useCase.findById(incidentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "case not found"));
    }

    @PostMapping("/{incidentId}/reviews")
    public CustomerServiceCaseDetail review(
            @PathVariable String incidentId,
            @RequestHeader("X-Operator-Id") String operatorRef,
            @RequestBody ReviewRequest request
    ) {
        String operation = request.decision() == CustomerServiceDecision.NO_CONTACT_CONFIRMED
                ? "approval.propose" : "customer.review";
        return Observation.createNotStarted(operation, observationRegistry)
                .lowCardinalityKeyValue("customer.decision", request.decision().name())
                .observe(() -> executeReview(incidentId, operatorRef, request));
    }

    /** 该路径由安全过滤器要求独立审批凭证；主管权限在 DevicePlatform Admin BFF 校验。 */
    @PostMapping("/{incidentId}/closure-proposals/{proposalId}/decision")
    public CustomerServiceCaseDetail decideClosureProposal(
            @PathVariable String incidentId,
            @PathVariable String proposalId,
            @RequestHeader("X-Operator-Id") String operatorRef,
            @RequestBody ClosureDecisionRequest request
    ) {
        var observation = Observation.createNotStarted("approval.decide", observationRegistry)
                .lowCardinalityKeyValue("approval.decision", request.approved() ? "approved" : "rejected");
        return observation.observe(() -> executeClosureDecision(incidentId, proposalId, operatorRef, request));
    }

    private CustomerServiceCaseDetail executeReview(
            String incidentId, String operatorRef, ReviewRequest request
    ) {
        try {
            return useCase.submitReview(new SubmitCustomerServiceReviewCommand(
                    incidentId, request.expectedVersion(), operatorRef, request.decision(), request.note()));
        } catch (CustomerServiceCaseNotFoundException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "case not found", exception);
        } catch (CustomerServiceCaseConflictException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "case changed, refresh before submitting again", exception);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    private CustomerServiceCaseDetail executeClosureDecision(
            String incidentId,
            String proposalId,
            String operatorRef,
            ClosureDecisionRequest request
    ) {
        try {
            CustomerServiceCaseDetail result = useCase.decideClosureProposal(new DecideClosureProposalCommand(
                    incidentId, proposalId, request.expectedVersion(), operatorRef,
                    request.approved(), request.note()));
            result.closureProposals().stream()
                    .filter(proposal -> proposal.proposalId().equals(proposalId))
                    .filter(proposal -> proposal.decidedAt() != null)
                    .findFirst()
                    .ifPresent(proposal -> Timer.builder("cardio_ops.approval.wait.duration")
                            .description("Time between closure proposal and supervisor decision")
                            .tag("decision", proposal.status().name())
                            .publishPercentileHistogram()
                            .register(meterRegistry)
                            .record(Duration.between(proposal.createdAt(), proposal.decidedAt())));
            return result;
        } catch (CustomerServiceCaseNotFoundException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "case or proposal not found", exception);
        } catch (CustomerServiceCaseConflictException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "case or proposal changed, refresh before deciding", exception);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    /**
     * @param expectedVersion 页面读取到的案件版本；服务端不会接受陈旧版本覆盖新结论
     * @param decision 三个已确认客服动作之一
     * @param note 必填处理依据，最多 1000 字符
     */
    public record ReviewRequest(long expectedVersion, CustomerServiceDecision decision, String note) {
    }

    /** @param approved true=同意关单；false=驳回并继续人工跟进。 */
    public record ClosureDecisionRequest(long expectedVersion, boolean approved, String note) {
    }
}
