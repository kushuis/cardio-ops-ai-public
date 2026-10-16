package io.cardioops.domain.agent;

/**
 * 档案可以授权的能力名，不是 Java 方法名。应传未传这条生产路径用到的是上下文、传输窗口、当前网关、改期历史，以及本地知识检索。
 * 知识检索只用来解释，不能拿去替代设备事实，也不能拿去算 recovered。
 */
public enum ToolCapability {
    /** 读取某个应传未传 Incident 的应传窗口、历史网关状态和最近设备快照。 */
    GET_SCHEDULED_MISS_CONTEXT,
    /** 在当前 Incident 限定的时间窗内查询网关/小程序传输批次。 */
    QUERY_TRANSMISSIONS_IN_WINDOW,
    /** 查询当前网关快照，用于判断历史问题现在是否仍可能持续。 */
    GET_CURRENT_GATEWAY_SNAPSHOT,
    /** 查询云端可见的广播参数修改记录；无法伪造程控仪尚未同步的本地修改。 */
    QUERY_SCHEDULE_CHANGE_HISTORY,
    /**
     * 按当前设备 Profile、SOP 标签和事件发生时间检索已发布知识。
     * 知识只用于解释和客服操作参考，不能替代 DevicePlatform 实时证据或 Java 恢复规则。
     */
    SEARCH_VERSIONED_KNOWLEDGE,
    /** 查询设备族、型号、固件和绑定关系等最小上下文，是所有调查的第一步。 */
    GET_DEVICE_CONTEXT,
    /** 查询蓝牙网关在线状态、最后心跳和网络摘要，用于定位家庭网关链路问题。 */
    GET_GATEWAY_SNAPSHOT,
    /** 查询网关/小程序/App 的历史传输结果，用于识别连续失败和通道切换。 */
    GET_TRANSMISSION_HISTORY,
    /** 查询 ECG 解析、报告生成等云端任务状态，区分“没上传”和“已上传但仍在处理”。 */
    GET_PROCESSING_JOB_STATUS,
    /** 查询既有参数操作的设备回读结果；查询本身不包含写权限。 */
    GET_PARAMETER_READBACK,
    /** 未知固件或 Profile 冲突时返回支持说明，避免模型猜测设备能力。 */
    GET_SUPPORTED_PROFILE_HELP,
    /** 能力目录里的名字。应传未传这条生产路径不装配它。 */
    CREATE_PATIENT_SERVICE_CASE,
    /** 能力目录里的名字。应传未传这条生产路径不装配它。 */
    PROPOSE_CLOUD_JOB_RETRY,
    /** 能力目录里的名字。应传未传这条生产路径不装配它。 */
    PROPOSE_DEVICE_PARAMETER_ACTION
}
