package io.cardioops.application.agentrun;

import io.cardioops.domain.agent.AgentRun;

import java.util.Optional;

/**
 * 共用任务队列的端口。只负责领取、读取和标记运行。它不知道这单最后交给哪一种调查。
 */
public interface AgentRunStore {

    /**
     * 原子领取最早的 QUEUED 任务，或租约已经过期且仍可恢复的 RUNNING 任务。
     * 多 Worker 下同一时刻只能有一个实例取得新的 leaseToken。
     */
    Optional<AgentRun> claimNextRunnableRun(AgentRunClaimPolicy policy);

    /** 按 ID 读取运行快照，供具体业务 Graph 通过稳定 ID 恢复上下文。 */
    Optional<AgentRun> findRun(String runId);

    /** 延长当前租约；返回 false 表示任务已结束或租约已经被其他 Worker 接管。 */
    boolean renewLease(String runId, String leaseToken, java.time.Duration leaseDuration);

    /** 将当前租约持有者成功处理的 Run 标记为 COMPLETED，并释放 active_slot。 */
    void markCompleted(String runId, String leaseToken);

    /** 将当前租约持有者遇到的技术失败写入 Run；errorCode 不能包含患者数据。 */
    void markFailed(String runId, String leaseToken, String errorCode);
}
