package io.cardioops.infrastructure.persistence.lowbattery;

import io.cardioops.application.agentrun.AgentRunStore;
import io.cardioops.application.incident.IncidentStore;
import io.cardioops.application.lowbattery.LowBatteryInvestigationCompletionUseCase;
import io.cardioops.application.lowbattery.LowBatteryInvestigationStore;
import io.cardioops.domain.lowbattery.LowBatteryInvestigationResult;
import org.springframework.transaction.support.TransactionTemplate;

/** 结果、Run 完成和案件转人工在同一事务提交。 */
public final class TransactionalLowBatteryInvestigationCompletion
        implements LowBatteryInvestigationCompletionUseCase {
    private final LowBatteryInvestigationStore results;
    private final AgentRunStore runs;
    private final IncidentStore incidents;
    private final TransactionTemplate transactions;
    public TransactionalLowBatteryInvestigationCompletion(LowBatteryInvestigationStore results,
            AgentRunStore runs, IncidentStore incidents, TransactionTemplate transactions) {
        this.results = results; this.runs = runs; this.incidents = incidents; this.transactions = transactions;
    }
    @Override public void complete(String runId, String leaseToken, LowBatteryInvestigationResult result) {
        if (!runId.equals(result.runId())) throw new IllegalArgumentException("RUN_RESULT_ID_MISMATCH");
        transactions.executeWithoutResult(status -> {
            results.saveResult(result);
            runs.markCompleted(runId, leaseToken);
            incidents.recordLowBatteryInvestigationCompleted(result.incidentId(), result.completedAt());
        });
    }
}
