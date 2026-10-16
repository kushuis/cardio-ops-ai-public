package io.cardioops.agent.lowbattery;

import io.cardioops.application.agentrun.AgentRunHandler;
import io.cardioops.application.agentrun.AgentRunLeaseGuard;
import io.cardioops.application.incident.IncidentStore;
import io.cardioops.application.lowbattery.LowBatteryInvestigationCompletionUseCase;
import io.cardioops.domain.agent.AgentRun;
import io.cardioops.domain.incident.IncidentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;

/** Dispatcher 只把低电量 Run 交给这张场景图。 */
public final class LowBatteryAgentRunHandler implements AgentRunHandler {
    private static final Logger log = LoggerFactory.getLogger(LowBatteryAgentRunHandler.class);
    private final LowBatteryInvestigationCompletionUseCase completion;
    private final LowBatteryInvestigationGraph graph;
    private final IncidentStore incidents;
    private final Clock clock;

    public LowBatteryAgentRunHandler(LowBatteryInvestigationCompletionUseCase completion,
                                     LowBatteryInvestigationGraph graph,
                                     IncidentStore incidents,
                                     Clock clock) {
        this.completion = completion;
        this.graph = graph;
        this.incidents = incidents;
        this.clock = clock;
    }

    @Override public boolean supports(IncidentType type) { return type == IncidentType.DEVICE_LOW_BATTERY; }

    @Override public void handle(AgentRun run, AgentRunLeaseGuard guard) {
        log.info("low battery investigation started runId={} incidentId={}", run.runId(), run.incidentId());
        try {
            var result = graph.execute(run.runId(), guard);
            guard.assertLeaseActive();
            completion.complete(run.runId(), run.leaseToken(), result);
            log.info("low battery investigation completed runId={} incidentId={} impactAssessment={} gatewayAvailable={}",
                    run.runId(), run.incidentId(), result.impactAssessment(), result.gatewayAvailable());
        } catch (RuntimeException exception) {
            if (LowBatteryExecutionSignals.leaseLost(exception)) {
                throw exception;
            }
            if (LowBatteryExecutionSignals.insufficientEvidence(exception)) {
                guard.assertLeaseActive();
                var result = LowBatteryExecutionSignals.insufficientResult(run, clock.instant());
                completion.complete(run.runId(), run.leaseToken(), result);
                log.info("low battery investigation completed with insufficient evidence runId={} incidentId={}",
                        run.runId(), run.incidentId());
                return;
            }
            // 其他技术失败仍交给 Worker 标 FAILED，但案件不能停在 DETECTED，否则人审会 409。
            // 写案件状态前再检查一次，防止已经丢租约的旧 Worker 覆盖新 Worker 的处理结果。
            guard.assertLeaseActive();
            incidents.recordLowBatteryInvestigationCompleted(run.incidentId(), clock.instant());
            throw exception;
        }
    }
}
