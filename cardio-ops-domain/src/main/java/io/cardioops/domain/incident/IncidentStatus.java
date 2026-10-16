package io.cardioops.domain.incident;

/**
 * 案件的业务生命周期：发现、待人看、待批准关闭、继续跟进、已关闭。
 * 程序跑没跑完看 AgentRun。这里不把“调查结束”写成“客服已经处理完”。
 */
public enum IncidentStatus {
    /** 应传未传仍在自动复查；还没有需要客服接手的结论。 */
    DETECTED,
    /** 恢复后等待客服确认关单，或低电量、事件不全这类不运行 Agent 的案件等待客服查看。 */
    PENDING_HUMAN_REVIEW,
    /** 客服申请“无需联系并关单”，等待主管审批；此时案件仍未关闭。 */
    PENDING_CLOSURE_APPROVAL,
    /** 客服认为需要联系患者或暂时无法判断，案件仍需后续人工跟进。 */
    FOLLOW_UP_REQUIRED,
    /** 主管审批且恢复证据复验通过，案件业务闭环；关闭后不能继续提交结论。 */
    CLOSED
}
