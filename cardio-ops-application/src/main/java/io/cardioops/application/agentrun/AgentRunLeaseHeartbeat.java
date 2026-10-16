package io.cardioops.application.agentrun;

import io.cardioops.domain.agent.AgentRun;

/**
 * 模型和工具还在跑的时候把数据库租约续上。不续的话，别的 Worker 会把还活着的执行当成过期接走。
 */
public interface AgentRunLeaseHeartbeat {

    /** 开始为本次领取续租；关闭返回句柄必须停止心跳。 */
    LeaseHandle start(AgentRun run);

    interface LeaseHandle extends AgentRunLeaseGuard, AutoCloseable {
        @Override
        void close();
    }
}
