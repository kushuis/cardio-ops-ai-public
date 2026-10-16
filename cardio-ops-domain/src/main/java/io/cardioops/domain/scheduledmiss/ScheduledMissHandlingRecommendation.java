package io.cardioops.domain.scheduledmiss;

/**
 * 给客服的两种建议：无需联系，或者人工复核。调查本身不联系患者。
 */
public enum ScheduledMissHandlingRecommendation {
    /** 已有自动唤醒恢复证据，或旧广播时间已被成功修改，无需为旧窗口联系患者。 */
    NO_CONTACT_NEEDED,
    /** 自动唤醒是否异常仍需人判断，由客服决定是否联系患者。 */
    HUMAN_REVIEW
}
