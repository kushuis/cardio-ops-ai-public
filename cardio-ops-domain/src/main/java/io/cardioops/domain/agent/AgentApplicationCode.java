package io.cardioops.domain.agent;

/**
 * 应用入口的稳定代号。每个入口有自己的角色和工具白名单。
 * 换一句提示词不能把另一个入口的权限借过来。
 */
public enum AgentApplicationCode {
    /** 运营端设备调查助手，本期唯一启用的入口，可查看运维证据但仍不能自动写植入设备。 */
    OPS_INVESTIGATOR,
    /** 面向患者小程序/App 的低权限助手预留，未来只暴露患者可理解且可见的信息。 */
    PATIENT_ASSISTANT,
    /** 面向医生端的辅助入口预留，未来需独立医疗语义、权限、审计和评测。 */
    DOCTOR_COPILOT
}
