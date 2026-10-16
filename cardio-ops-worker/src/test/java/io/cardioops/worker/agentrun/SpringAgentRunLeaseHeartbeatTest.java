package io.cardioops.worker.agentrun;

import io.cardioops.application.agentrun.AgentRunClaimPolicy;
import io.cardioops.application.agentrun.AgentRunLeaseHeartbeat;
import io.cardioops.application.agentrun.AgentRunLeaseLostException;
import io.cardioops.application.agentrun.AgentRunStore;
import io.cardioops.domain.agent.AgentRun;
import io.cardioops.domain.agent.AgentRunStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证心跳拒绝续租会通知业务线程停止，而不是只留下日志。 */
class SpringAgentRunLeaseHeartbeatTest {

    private static final Instant NOW = Instant.parse("2026-09-06T03:00:00Z");

    @Test
    void marksGuardLostWhenDatabaseRejectsLeaseRenewal() throws InterruptedException {
        var renewalAttempted = new CountDownLatch(1);
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.initialize();
        try {
            var properties = new AgentRunWorkerProperties(
                    "heartbeat-test", Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(10), 3);
            var heartbeat = new SpringAgentRunLeaseHeartbeat(
                    new RejectingLeaseStore(renewalAttempted), properties, scheduler,
                    new SimpleMeterRegistry(), Clock.fixed(NOW, ZoneOffset.UTC));

            AgentRunLeaseHeartbeat.LeaseHandle handle = heartbeat.start(runningRun());

            assertThat(renewalAttempted.await(1, TimeUnit.SECONDS)).isTrue();
            // renewalAttempted 在存储层返回 false 前触发；后台线程还需要极短时间把 Guard
            // 标记为 lost。使用有上限的等待，避免主线程恰好抢在状态更新前造成偶发失败。
            awaitLeaseLost(handle);
            assertThat(handle.leaseLost()).isTrue();
            assertThatThrownBy(handle::assertLeaseActive)
                    .isInstanceOf(AgentRunLeaseLostException.class)
                    .hasMessage("AGENT_RUN_LEASE_LOST");
            handle.close();
        } finally {
            scheduler.shutdown();
        }
    }

    private static void awaitLeaseLost(AgentRunLeaseHeartbeat.LeaseHandle handle)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!handle.leaseLost() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }

    private static AgentRun runningRun() {
        return new AgentRun(
                "run-1", "incident-1", "event-1", "thread-1", AgentRunStatus.RUNNING,
                "demo-icm-v2", "trace-1", 1, "worker-1", "lease-1",
                NOW.plus(Duration.ofMinutes(1)), NOW, NOW);
    }

    private record RejectingLeaseStore(CountDownLatch renewalAttempted) implements AgentRunStore {
        @Override public Optional<AgentRun> claimNextRunnableRun(AgentRunClaimPolicy policy) { return Optional.empty(); }
        @Override public Optional<AgentRun> findRun(String runId) { return Optional.empty(); }
        @Override public boolean renewLease(String runId, String leaseToken, Duration leaseDuration) {
            renewalAttempted.countDown();
            return false;
        }
        @Override public void markCompleted(String runId, String leaseToken) { }
        @Override public void markFailed(String runId, String leaseToken, String errorCode) { }
    }
}
