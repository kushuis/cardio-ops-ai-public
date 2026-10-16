package io.cardioops.agent.scheduledmiss;

import io.cardioops.application.agentrun.AgentRunHandler;
import io.cardioops.application.agentrun.AgentRunLeaseGuard;
import io.cardioops.application.scheduledmiss.ScheduledMissInvestigationCompletionUseCase;
import io.cardioops.domain.agent.AgentRun;
import io.cardioops.domain.incident.IncidentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * 应传未传这种 Incident 才接的 Handler。别的类型 supports 直接返回 false。
 *
 * <p>它做的事很窄：把已经领成 RUNNING 的 Run 连同租约交给图，图走到 END 之后再查一次租约，
 * 然后把结果交给由 leaseToken 把守的完成事务。图跑完只是调查结束。
 * HUMAN_REVIEW 是结果里的建议，人在案件服务里另行处理。人工批准不是这张图上的中断。
 * 图里的 Checkpoint 只在执行线程崩了以后，让下一次领取从已经落盘的节点接着跑。</p>
 *
 * <p>
 * 最后一个节点和这个完成事务之间还有一段缝，租约可能就在这段缝里被别人接走。
 * 先查一次能早点停。真正防覆盖的是事务里的 leaseToken，这一次检查替代不了它。</p>
 */
public final class ScheduledMissAgentRunHandler implements AgentRunHandler {

    private static final Logger log = LoggerFactory.getLogger(ScheduledMissAgentRunHandler.class);
    private final ScheduledMissInvestigationCompletionUseCase completionUseCase;
    private final ScheduledMissInvestigationGraph graph;

    public ScheduledMissAgentRunHandler(
            ScheduledMissInvestigationCompletionUseCase completionUseCase,
            ScheduledMissInvestigationGraph graph
    ) {
        this.completionUseCase = Objects.requireNonNull(completionUseCase, "completionUseCase must not be null");
        this.graph = Objects.requireNonNull(graph, "graph must not be null");
    }

    @Override
    public boolean supports(IncidentType incidentType) {
        return incidentType == IncidentType.SCHEDULED_GATEWAY_TRANSMISSION_MISSED;
    }

    @Override
    public void handle(AgentRun run, AgentRunLeaseGuard leaseGuard) {
        var result = graph.execute(run.runId(), leaseGuard);
        leaseGuard.assertLeaseActive();
        completionUseCase.complete(run.runId(), run.leaseToken(), result);
        log.info("scheduled miss result persisted runId={} incidentId={} recovered={} recommendation={}",
                run.runId(), run.incidentId(), result.recovered(), result.recommendation());
    }
}
