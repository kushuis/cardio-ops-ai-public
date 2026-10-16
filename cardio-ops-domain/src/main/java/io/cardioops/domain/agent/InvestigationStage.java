package io.cardioops.domain.agent;

/**
 * 调查走到哪一步的业务说法：已请求、上下文装好、证据收齐、完成、失败。
 * 这不是图上的技术节点名。以后换一种设备或换人来查，阶段名还可以共用。
 */
public enum InvestigationStage {
    /** 已收到调查请求，但还没有读取患者/设备上下文。 */
    REQUESTED,
    /** 已加载本次调查所需的设备型号、传输方式等上下文。 */
    CONTEXT_LOADED,
    /** 已通过工具查询到网关、传输记录等客观证据。 */
    EVIDENCE_COLLECTED,
    /** 本次调查正常结束。 */
    COMPLETED,
    /** 调查因工具、模型或系统异常而失败。 */
    FAILED
}
