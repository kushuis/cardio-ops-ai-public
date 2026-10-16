package io.cardioops.worker.agentrun;

import io.cardioops.application.agentrun.AgentRunLeaseHeartbeat;
import io.cardioops.application.agentrun.AgentRunStore;
import io.cardioops.domain.agent.AgentRun;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.cardioops.application.agentrun.AgentRunLeaseLostException;

/**
 * 独立线程给正在跑的任务续租。续租条件是 token 还对、租约还没过期。数据库拒绝续租，就把本地开关打成丢失，业务线程下一次检查停下来。
 * 心跳出错但租约还没到期，先不当成丢失，避免数据库抖一下就把还合法的执行杀掉。
 * 关闭时取消调度。本地开关丢失不是 markFailed。
 *
 *
 * 一次数据库错误不等于 token 已经给了别人。租约期限还在，就再试。确认拒绝，或者过期之后还在失败，才让业务线程停。
 */
public final class SpringAgentRunLeaseHeartbeat implements AgentRunLeaseHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(SpringAgentRunLeaseHeartbeat.class);
    private final AgentRunStore store;
    private final AgentRunWorkerProperties properties;
    private final TaskScheduler taskScheduler;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public SpringAgentRunLeaseHeartbeat(
            AgentRunStore store,
            AgentRunWorkerProperties properties,
            TaskScheduler taskScheduler,
            MeterRegistry meterRegistry,
            Clock clock
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.taskScheduler = Objects.requireNonNull(taskScheduler, "taskScheduler must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public LeaseHandle start(AgentRun run) {
        if (run.leaseToken() == null) {
            throw new IllegalArgumentException("RUNNING_AGENT_RUN_REQUIRES_LEASE_TOKEN");
        }
        var handle = new ActiveLeaseHandle(run.leaseExpiresAt());
        var future = taskScheduler.scheduleAtFixedRate(
                () -> renew(run, handle),
                Instant.now(clock).plus(properties.heartbeatInterval()),
                properties.heartbeatInterval());
        if (future == null) {
            throw new IllegalStateException("AGENT_RUN_HEARTBEAT_NOT_SCHEDULED");
        }
        handle.setFuture(future);
        return handle;
    }

    private void renew(AgentRun run, ActiveLeaseHandle handle) {
        if (handle.leaseLost()) {
            return;
        }
        Instant now = clock.instant();
        try {
            boolean renewed = store.renewLease(
                    run.runId(), run.leaseToken(), properties.leaseDuration());
            meterRegistry.counter("cardio_ops.agent.lease.heartbeat",
                    "result", renewed ? "renewed" : "rejected").increment();
            if (renewed) {
                handle.renewedUntil(now.plus(properties.leaseDuration()));
            } else {
                handle.markLost();
                log.warn("agent run lease lost because heartbeat was rejected runId={} attempt={}",
                        run.runId(), run.executionAttempt());
            }
        } catch (RuntimeException exception) {
            meterRegistry.counter("cardio_ops.agent.lease.heartbeat", "result", "error").increment();
            // 数据库瞬时异常不能立刻等价为“已被接管”。但是超过最近一次确认的到期时间后，
            // 本 Worker 不得继续执行任何新步骤，直到能再次证明自己仍持有租约。
            if (!now.isBefore(handle.knownExpiresAt())) {
                //过期
                handle.markLost();
                log.warn("agent run lease lost after heartbeat error and expiry runId={} attempt={} errorType={}",
                        run.runId(), run.executionAttempt(), exception.getClass().getSimpleName());
            } else {
                log.error("agent run lease heartbeat failed but current lease is still valid runId={} attempt={}",
                        run.runId(), run.executionAttempt(), exception);
            }
        }
    }

    /** 心跳线程写入、业务线程读取的轻量协作式取消对象。 */
    private static final class ActiveLeaseHandle implements LeaseHandle {
        private final AtomicBoolean lost = new AtomicBoolean(false);
        private final AtomicReference<Instant> knownExpiresAt;
        private volatile ScheduledFuture<?> future;

        private ActiveLeaseHandle(Instant initialExpiresAt) {
            this.knownExpiresAt = new AtomicReference<>(Objects.requireNonNull(
                    initialExpiresAt, "running AgentRun must have leaseExpiresAt"));
        }

        private void setFuture(ScheduledFuture<?> future) {
            this.future = future;
        }

        private void renewedUntil(Instant expiresAt) {
            knownExpiresAt.set(expiresAt);
        }

        private Instant knownExpiresAt() {
            return knownExpiresAt.get();
        }

        private void markLost() {
            lost.set(true);
            ScheduledFuture<?> scheduledFuture = future;
            if (scheduledFuture != null) {
                scheduledFuture.cancel(false);
            }
        }

        @Override
        public void assertLeaseActive() {
            if (lost.get()) {
                throw new AgentRunLeaseLostException("AGENT_RUN_LEASE_LOST");
            }
        }

        @Override
        public boolean leaseLost() {
            return lost.get();
        }

        @Override
        public void close() {
            ScheduledFuture<?> scheduledFuture = future;
            if (scheduledFuture != null) {
                scheduledFuture.cancel(false);
            }
        }
    }
}
