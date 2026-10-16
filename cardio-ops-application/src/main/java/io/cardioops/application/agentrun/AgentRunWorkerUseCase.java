package io.cardioops.application.agentrun;

/**
 * 公共 Worker 每一轮领一条并分发出去。领不到就告诉调用方这轮可以停。
 */
public interface AgentRunWorkerUseCase {

    /**
     * @return true 表示本次领取并处理了一项任务；false 表示队列当前为空
     */
    boolean executeNext();
}
