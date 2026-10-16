package io.cardioops.worker.agentrun;

import io.cardioops.application.agentrun.AgentRunExecutionObserver;
import io.cardioops.domain.agent.AgentRun;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Objects;

/**
 * 把领取、完成、失败、丢租约记成低基数指标。标签里的 recovered 是“这次是不是第 2 次及以后的领取”，不是调查结论里的 recovered。
 * runId 不进标签。指标写失败由 Worker 吞掉，不改变这单的结果。
 */
public final class MicrometerAgentRunExecutionObserver implements AgentRunExecutionObserver {

    private final MeterRegistry meterRegistry;

    public MicrometerAgentRunExecutionObserver(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    @Override
    public void claimed(AgentRun run) {
        meterRegistry.counter("cardio_ops.agent.run.claimed",
                "recovered", Boolean.toString(run.recoveredExecution())).increment();
    }

    @Override
    public void completed(AgentRun run, Duration duration) {
        record("completed", duration);
    }

    @Override
    public void failed(AgentRun run, String errorCode, Duration duration) {
        // errorCode 类型可能不断增加，不作为指标标签，避免时间序列基数失控；详细类型留在日志。
        record("failed", duration);
    }

    @Override
    public void leaseLost(AgentRun run, Duration duration) {
        record("lease_lost", duration);
    }

    private void record(String outcome, Duration duration) {
        meterRegistry.counter("cardio_ops.agent.run.finished", "outcome", outcome).increment();
        Timer.builder("cardio_ops.agent.run.duration")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(duration);
    }
}
