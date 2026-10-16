package io.cardioops.worker.agentrun;

import io.cardioops.application.agentrun.AgentRunWorkerUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

/**
 * 调度线程上的领取循环。它不认识应传未传，也不跑图，只是一遍遍叫 Worker 去领下一条。
 *
 * <p>每一轮最多 10 条就停。poll 跑在 @Scheduled 的线程上，fixedDelay 要等这一轮返回才开始算。
 * 积压大的时候如果一直领到空，这条调度线程就一直不回来，跟它共用调度器的定时任务也进不来。
 * 租约心跳有自己的线程，不靠这一条。</p>
 *
 * <p>
 * 单轮处理数量设有上限，防止调度线程长期占用并延迟后续 fixedDelay 调度。</p>
 */
public final class AgentRunPoller {

    private static final int MAX_RUNS_PER_POLL = 10;
    private static final Logger log = LoggerFactory.getLogger(AgentRunPoller.class);
    private final AgentRunWorkerUseCase worker;

    public AgentRunPoller(AgentRunWorkerUseCase worker) {
        this.worker = Objects.requireNonNull(worker, "worker must not be null");
    }

    @Scheduled(fixedDelayString = "${cardio-ops.agent.worker.poll-delay:5s}")
    public void poll() {
        int processed = 0;
        while (processed < MAX_RUNS_PER_POLL && worker.executeNext()) {
            processed++;
        }
        if (processed > 0) {
            log.info("agent run poll batch completed processed={}", processed);
        }
    }
}
