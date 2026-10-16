package io.cardioops.agent.agentrun;

import io.cardioops.application.agentrun.AgentRunStore;
import io.cardioops.application.agentrun.AgentRunWorkerUseCase;
import io.cardioops.application.agentrun.AgentRunClaimPolicy;
import io.cardioops.application.agentrun.AgentRunExecutionObserver;
import io.cardioops.application.agentrun.AgentRunLeaseHeartbeat;
import io.cardioops.application.agentrun.AgentRunLeaseLostException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * 所有 Agent 业务共用的 Worker。它只做三件事：把任务领成 RUNNING、交给分发器、记下技术失败。
 * 应传未传怎么查、图长什么样，它都不知道。
 *
 * <p>领取靠数据库里的租约，同一条 Run 不会被两个 Worker 同时当成自己的。
 * 心跳发现租约没了，就停掉本地执行，不能 markFailed。这条 Run 可能已经被下一个 Worker 接走，
 * 旧进程再写失败，会把别人的状态盖掉。markFailed 自己如果因为租约对不上而失败，也只记日志。</p>
 *
 * <p>attempt 大于 1 时 recoveredExecution 为真，表示上次执行没正常结束，这次是把同一条执行接回来。
 * 从哪个节点继续，不在这个类里决定。Handler 进图的时候，Checkpoint 只用来续跑崩溃的线程。
 * 人工批准不是图上的中断，这里也没有“等人点头再领取”的暂停。</p>
 *
 * <p>这一层的 Span 叫 agent.run，一次领取一条。节点、模型、工具是后面的阶段，各自另开 Span。
 * 要不要导出看 management.opentelemetry.enabled，也就是 CARDIO_OPS_OTEL_ENABLED，默认关着。
 * 没开就还在进程里观察，不会往 Collector 推。MDC 里的 businessTraceId 是业务关联号，
 * 不能拿去覆盖 Micrometer 写进去的真正 traceId。</p>
 *
 * <p>指标写失败只能少一次观测，不能改这条 Run 的结果。所以 observer 的异常被留下，业务异常继续走失败处理。</p>
 *
 * <p>
 * 丢租约说明新的 token 可能已经在别的 Worker 上。这里再标失败，会把还在跑的那次执行判死。停掉自己就行。</p>
 */
public final class AgentRunWorker implements AgentRunWorkerUseCase {

    private static final Logger log = LoggerFactory.getLogger(AgentRunWorker.class);
    private final AgentRunStore runStore;
    private final AgentRunDispatcher dispatcher;
    private final AgentRunClaimPolicy claimPolicy;
    private final AgentRunLeaseHeartbeat heartbeat;
    private final AgentRunExecutionObserver observer;
    private final ObservationRegistry observationRegistry;
    private final Clock clock;

    public AgentRunWorker(
            AgentRunStore runStore,
            AgentRunDispatcher dispatcher,
            AgentRunClaimPolicy claimPolicy,
            AgentRunLeaseHeartbeat heartbeat,
            AgentRunExecutionObserver observer,
            Clock clock
    ) {
        this(runStore, dispatcher, claimPolicy, heartbeat, observer, clock, ObservationRegistry.NOOP);
    }

    public AgentRunWorker(
            AgentRunStore runStore,
            AgentRunDispatcher dispatcher,
            AgentRunClaimPolicy claimPolicy,
            AgentRunLeaseHeartbeat heartbeat,
            AgentRunExecutionObserver observer,
            Clock clock,
            ObservationRegistry observationRegistry
    ) {
        this.runStore = Objects.requireNonNull(runStore, "runStore must not be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        this.claimPolicy = Objects.requireNonNull(claimPolicy, "claimPolicy must not be null");
        this.heartbeat = Objects.requireNonNull(heartbeat, "heartbeat must not be null");
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.observationRegistry = Objects.requireNonNull(observationRegistry,
                "observationRegistry must not be null");
    }

    /**
     * 领到一条，就把这条的技术生命周期跑完。领不到返回 false，Poller 就结束这一轮。
     *
     * <p>Span 上的 agent.recovered 是“这次领取是不是第 2 次及以后”，不是调查结论里的 recovered。
     * 调查那个位要等校验拿证据重算，模型拿不到，这个类里也没有。
     * profileId 和 outcome 是有限集合，可以当指标标签。runId、incidentId 无界，只进 Trace。</p>
     *
     * <p>
     * 不是。这里是执行代际，attempt 大于 1 就是在接上次没跑完的线程。业务上算不算恢复，是结果里的另一个字段。</p>
     */
    @Override
    public boolean executeNext() {
        var claimed = runStore.claimNextRunnableRun(claimPolicy);
        if (claimed.isEmpty()) {
            log.debug("agent run poll completed result=empty");
            return false;
        }
        var run = claimed.orElseThrow();
        observeSafely(() -> observer.claimed(run), run.runId(), "claimed");
        var startedAt = clock.instant();
        var observation = Observation.createNotStarted("agent.run", observationRegistry)
                .contextualName("agent run")
                .lowCardinalityKeyValue("agent.profile", run.profileId() == null ? "unresolved" : run.profileId())
                .lowCardinalityKeyValue("agent.recovered", Boolean.toString(run.recoveredExecution()))
                .highCardinalityKeyValue("cardio.run.id", run.runId())
                .highCardinalityKeyValue("cardio.incident.id", run.incidentId())
                .highCardinalityKeyValue("cardio.correlation.id", run.traceId())
                .start();
        String traceOutcome = "succeeded";
        try (var observationScope = observation.openScope();
             var ignoredRun = MDC.putCloseable("runId", run.runId());
             var ignoredIncident = MDC.putCloseable("incidentId", run.incidentId());
             // 业务事件上的 traceId 是关联号。Micrometer 已经把真正的 traceId 写进 MDC，这里换一个键。
             var ignoredBusinessTrace = MDC.putCloseable("businessTraceId", run.traceId());
             var leaseGuard = heartbeat.start(run)) {
            log.info("agent run claimed runId={} incidentId={} profileId={} attempt={} recovered={} workerId={}",
                    run.runId(), run.incidentId(), run.profileId(), run.executionAttempt(),
                    run.recoveredExecution(), run.leaseOwner());
            try {
                dispatcher.dispatch(run, leaseGuard);
                observeSafely(() -> observer.completed(
                        run, Duration.between(startedAt, clock.instant())), run.runId(), "completed");
            } catch (AgentRunLeaseLostException leaseLost) {
                traceOutcome = "lease_lost";
                observation.error(leaseLost);
                // 任务可能已经被别人接走。这里只能停本地执行，不能 markFailed。
                observeSafely(() -> observer.leaseLost(
                        run, Duration.between(startedAt, clock.instant())), run.runId(), "lease_lost");
                log.warn("agent run stopped because lease was lost runId={} incidentId={} attempt={}",
                        run.runId(), run.incidentId(), run.executionAttempt());
            } catch (RuntimeException exception) {
                traceOutcome = "failed";
                observation.error(exception);
                String errorCode = exception.getClass().getSimpleName();
                try {
                    runStore.markFailed(run.runId(), run.leaseToken(), errorCode);
                } catch (RuntimeException leaseConflict) {
                    // 写入失败时租约已转移，当前 Worker 不得覆盖新持有者更新的状态。
                    log.warn("agent run failure not persisted because lease was lost runId={} attempt={} errorCode={}",
                            run.runId(), run.executionAttempt(), errorCode);
                }
                observeSafely(() -> observer.failed(
                        run, errorCode, Duration.between(startedAt, clock.instant())), run.runId(), "failed");
                log.error("agent run failed runId={} incidentId={} attempt={} errorCode={}",
                        run.runId(), run.incidentId(), run.executionAttempt(), errorCode, exception);
            }
        } finally {
            observation.lowCardinalityKeyValue("outcome", traceOutcome).stop();
        }
        return true;
    }

    private void observeSafely(Runnable action, String runId, String signal) {
        try {
            action.run();
        } catch (RuntimeException metricsFailure) {
            // 指标坏了只少一次观测。这条 Run 该完成还是完成，该失败还是失败。
            log.warn("agent run metric recording failed runId={} signal={} errorType={}",
                    runId, signal, metricsFailure.getClass().getSimpleName());
        }
    }
}
