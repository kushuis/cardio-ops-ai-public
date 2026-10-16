package io.cardioops.application.agentrun;

import io.cardioops.domain.agent.AgentRun;

import java.time.Duration;

/**
 * 运行生命周期的观察口。Worker 不直接碰 Micrometer，指标坏了才不会把业务结果一起带歪。
 */
public interface AgentRunExecutionObserver {

    void claimed(AgentRun run);

    void completed(AgentRun run, Duration duration);

    void failed(AgentRun run, String errorCode, Duration duration);

    /** 租约丢失代表本 Worker 停止执行，不等于业务调查失败。 */
    void leaseLost(AgentRun run, Duration duration);
}
