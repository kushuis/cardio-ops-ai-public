package io.cardioops.domain.customerservice;

/**
 * 关闭建议的单级状态：待批、通过、拒绝、核验失败。没有再套一层通用工作流。
 */
public enum ClosureProposalStatus {
    /** 客服已提案，等待主管决定。 */
    PENDING,
    /** 主管同意且恢复证据复验通过，案件已关闭。 */
    APPROVED,
    /** 主管拒绝，案件继续人工跟进。 */
    REJECTED,
    /** 主管同意，但审批时证据已变化，案件退回人工复核。 */
    VERIFICATION_FAILED
}
