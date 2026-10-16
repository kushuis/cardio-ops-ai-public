package io.cardioops.worker.agentrun;

import io.cardioops.application.scheduledmiss.ScheduledMissRecheckQueueUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

/** 定期把到期的应传未传复查计划排成新的 AgentRun；每一轮仍会重新收集全部证据。 */
public final class ScheduledMissRecheckPoller {

    private static final Logger log = LoggerFactory.getLogger(ScheduledMissRecheckPoller.class);
    private static final int MAX_RECHECKS_PER_POLL = 20;
    private final ScheduledMissRecheckQueueUseCase queueUseCase;

    public ScheduledMissRecheckPoller(ScheduledMissRecheckQueueUseCase queueUseCase) {
        this.queueUseCase = Objects.requireNonNull(queueUseCase);
    }

    @Scheduled(fixedDelayString = "${cardio-ops.agent.recheck-poll-delay:30s}")
    public void poll() {
        int queued = queueUseCase.queueDueRechecks(MAX_RECHECKS_PER_POLL);
        if (queued > 0) {
            log.info("scheduled miss recheck poll completed queued={}", queued);
        }
    }
}
