package io.cardioops.infrastructure.persistence.scheduledmiss;

import io.cardioops.application.incident.IncidentStore;
import io.cardioops.application.scheduledmiss.ScheduledMissRecheckQueueUseCase;
import io.cardioops.application.scheduledmiss.ScheduledMissRecheckStore;
import io.cardioops.domain.agent.AgentRun;
import io.cardioops.domain.agent.AgentRunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** 锁计划、排 AgentRun、推进周期三步使用同一本地事务，避免同一次复查被多个 Worker 重复排队。 */
public final class TransactionalScheduledMissRecheckQueue implements ScheduledMissRecheckQueueUseCase {

    private static final Logger log = LoggerFactory.getLogger(TransactionalScheduledMissRecheckQueue.class);
    private final ScheduledMissRecheckStore recheckStore;
    private final IncidentStore incidentStore;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public TransactionalScheduledMissRecheckQueue(
            ScheduledMissRecheckStore recheckStore,
            IncidentStore incidentStore,
            TransactionTemplate transactionTemplate,
            Clock clock
    ) {
        this.recheckStore = Objects.requireNonNull(recheckStore);
        this.incidentStore = Objects.requireNonNull(incidentStore);
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public int queueDueRechecks(int limit) {
        Integer queued = transactionTemplate.execute(status -> {
            var now = clock.instant();
            int count = 0;
            for (var plan : recheckStore.lockDuePlans(now, limit)) {
                int recheckNumber = plan.recheckCount() + 1;
                String runId = UUID.randomUUID().toString();
                var run = new AgentRun(
                        runId, plan.incidentId(),
                        "scheduled-recheck:" + plan.incidentId() + ":" + recheckNumber,
                        "incident:" + plan.incidentId() + ":recheck:" + recheckNumber,
                        AgentRunStatus.QUEUED, plan.profileId(), UUID.randomUUID().toString(),
                        0, null, null, null, now, now);
                if (incidentStore.queueRunIfNoActive(run)) {
                    recheckStore.markQueued(plan, now);
                    count++;
                    log.info("scheduled miss recheck queued incidentId={} runId={} recheckNumber={} nextDueAt={}",
                            plan.incidentId(), runId, recheckNumber, plan.nextRecheckAt());
                }
            }
            return count;
        });
        return queued == null ? 0 : queued;
    }
}
