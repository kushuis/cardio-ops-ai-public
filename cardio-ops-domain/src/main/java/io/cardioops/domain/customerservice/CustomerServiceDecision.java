package io.cardioops.domain.customerservice;

/**
 * 客服对一次调查给出的三个动作：确认无需联系、需要联系患者、继续跟进。
 * 还没真正做的动作不先写进枚举，免得状态比业务跑得快。
 */
public enum CustomerServiceDecision {
    /** 客服认可无需联系患者，并提交关单审批；客服本人不能直接关闭案件。 */
    NO_CONTACT_CONFIRMED,
    /** 客服判断需要联系患者；当前只记录决定，不自动执行电话或短信。 */
    CONTACT_PATIENT_REQUIRED,
    /** 现有证据仍不足，保持案件开放并继续人工跟进。 */
    CONTINUE_FOLLOW_UP
}
