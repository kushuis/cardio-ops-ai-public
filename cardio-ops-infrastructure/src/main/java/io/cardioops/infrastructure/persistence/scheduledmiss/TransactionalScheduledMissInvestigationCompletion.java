package io.cardioops.infrastructure.persistence.scheduledmiss;

import io.cardioops.application.agentrun.AgentRunStore;
import io.cardioops.application.incident.IncidentStore;
import io.cardioops.application.scheduledmiss.ScheduledMissInvestigationCompletionUseCase;
import io.cardioops.application.scheduledmiss.ScheduledMissInvestigationStore;
import io.cardioops.application.scheduledmiss.ScheduledMissRecheckStore;
import io.cardioops.domain.scheduledmiss.ScheduledMissInvestigationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/**
 * 一个本地事务里做三件事：存调查结果、用 leaseToken 把运行标完成、按复查结果推进案件。
 * 还没复查完时保持自动跟踪。恢复后进入客服确认关单，三次仍未恢复才转人工跟进。不自动联系患者，也不自动关单。
 * 任一步失败就整单回滚，避免结果在、运行还挂着，或者运行完成了、案件状态却没一起落下。
 * 这个事务不包含 Checkpoint。节点快照是图在节点边界自己写的。
 *
 *
 * 模型节点还没过校验。提前改状态，失败回滚时案件已经被人看见。收尾事务放在校验之后。
 */
public final class TransactionalScheduledMissInvestigationCompletion
        implements ScheduledMissInvestigationCompletionUseCase {

    private static final Logger log = LoggerFactory.getLogger(TransactionalScheduledMissInvestigationCompletion.class);
    private final ScheduledMissInvestigationStore investigationStore;
    private final AgentRunStore agentRunStore;
    private final IncidentStore incidentStore;
    private final ScheduledMissRecheckStore recheckStore;
    private final TransactionTemplate transactionTemplate;

    public TransactionalScheduledMissInvestigationCompletion(
            ScheduledMissInvestigationStore investigationStore,
            AgentRunStore agentRunStore,
            IncidentStore incidentStore,
            ScheduledMissRecheckStore recheckStore,
            TransactionTemplate transactionTemplate
    ) {
        this.investigationStore = Objects.requireNonNull(investigationStore, "investigationStore must not be null");
        this.agentRunStore = Objects.requireNonNull(agentRunStore, "agentRunStore must not be null");
        this.incidentStore = Objects.requireNonNull(incidentStore, "incidentStore must not be null");
        this.recheckStore = Objects.requireNonNull(recheckStore, "recheckStore must not be null");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate must not be null");
    }

    @Override
    /**
     * runId 必须和结果上的一致。token 对不上，完成更新会失败，事务一起回滚，旧 Worker 不能把别人接走的单标完成。
     */
    public void complete(String runId, String leaseToken, ScheduledMissInvestigationResult result) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(leaseToken, "leaseToken must not be null");
        Objects.requireNonNull(result, "result must not be null");
        if (!runId.equals(result.runId())) {
            throw new IllegalArgumentException("RUN_RESULT_ID_MISMATCH");
        }
        try {
            transactionTemplate.executeWithoutResult(status -> {
                investigationStore.saveResult(result);
                agentRunStore.markCompleted(runId, leaseToken);
                var plan = recheckStore.findByIncidentId(result.incidentId());
                // 旧案件可能还没有复查计划。没有计划就不再自动复查，不能把整次完成回滚掉。
                boolean exhausted = plan
                        .map(existing -> !result.recovered() && existing.recheckCount() >= 3)
                        .orElse(!result.recovered());
                if (plan.isPresent() && (result.recovered() || exhausted)) {
                    recheckStore.stop(result.incidentId(), result.completedAt());
                }
                // 恢复只代表具备关单依据，仍要客服确认/主管审批；三次仍未恢复则直接停止调度并转人工跟进。
                incidentStore.recordScheduledMissInvestigationOutcome(
                        result.incidentId(), result.recovered(), exhausted, result.completedAt());
            });
            log.info("scheduled miss completion transaction committed runId={} incidentId={} recovered={}",
                    runId, result.incidentId(), result.recovered());
        } catch (RuntimeException exception) {
            log.warn("scheduled miss completion transaction rolled_back runId={} incidentId={} errorType={}",
                    runId, result.incidentId(), exception.getClass().getSimpleName());
            throw exception;
        }
    }
}
