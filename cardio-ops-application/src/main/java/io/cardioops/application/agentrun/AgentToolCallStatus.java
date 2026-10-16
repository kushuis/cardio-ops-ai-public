package io.cardioops.application.agentrun;

/**
 * 工具调用的三种技术结果：成功、被守卫拒绝、调用失败。租约丢失不是其中一种，不能伪装成工具失败。
 */
public enum AgentToolCallStatus {
    /** Tool 已通过 Guard 校验并成功返回。 */
    SUCCEEDED,
    /** Tool 在执行前被权限、范围或重复调用规则拒绝。 */
    REJECTED,
    /** Tool 已开始执行，但调用下游或处理结果时失败。 */
    FAILED
}
