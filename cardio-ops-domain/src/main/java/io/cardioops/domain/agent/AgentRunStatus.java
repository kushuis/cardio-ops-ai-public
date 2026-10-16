package io.cardioops.domain.agent;

/**
 * 这次程序跑到哪了：排队、执行中、完成、失败。
 * 它不表示客服已经联系过患者。调查成功但还要人看，仍然是完成，不是失败。
 */
public enum AgentRunStatus {
    /** 调查任务已与 Incident 同事务入库，等待后续 Worker 执行 Graph。 */
    QUEUED,
    /** Worker 已原子领取任务，同一 Run 不应再被其他实例并发执行。 */
    RUNNING,
    /** Graph 正常结束，且结构化结果已经通过 Java 规则校验并持久化。 */
    COMPLETED,
    /** 模型、工具或系统发生不可恢复的技术错误；不能用它表示“需要人工复核”。 */
    FAILED
}
