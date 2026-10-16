package io.cardioops.application.agentrun;

/**
 * 业务线程读的租约开关。心跳线程改它，图、模型和工具在各自的边界上问它还在不在。
 * 本地问过不等于写库时还握着租约，完成和失败仍然要带 leaseToken。
 *
 *
 * 同步调用掐不干净。返回后的下一次检查停掉后续步骤。真正防覆盖的是写库时的 token。
 */
public interface AgentRunLeaseGuard {

    /** 当前租约已经明确丢失时抛出异常，调用者不得继续执行新的业务步骤。 */
    void assertLeaseActive();

    /** 仅用于日志、指标和测试；业务停止应通过 assertLeaseActive() 完成。 */
    boolean leaseLost();

    /**
     * 测试和没有后台租约的路径用。assert 什么都不做。生产领取走真的心跳开关。
     */
    static AgentRunLeaseGuard alwaysActive() {
        return AlwaysActiveLeaseGuard.INSTANCE;
    }

    enum AlwaysActiveLeaseGuard implements AgentRunLeaseGuard {
        INSTANCE;

        @Override
        public void assertLeaseActive() {
            // 无后台 Worker 的纯单元测试不需要数据库租约。
        }

        @Override
        public boolean leaseLost() {
            return false;
        }
    }
}
