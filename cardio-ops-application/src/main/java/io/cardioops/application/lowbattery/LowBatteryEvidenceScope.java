package io.cardioops.application.lowbattery;

/** 由可信运行上下文绑定的 MCP 查询范围，模型不能修改设备或关联键。 */
public record LowBatteryEvidenceScope(
        /** CardioOps 本地案件 ID，用于工具审计。 */
        String incidentId,
        /** DevicePlatform 签发的 BATTERY 关联键，精确指向一条患者服务记录。 */
        String correlationRef,
        /** 从 Incident 注入的设备序列号，服务端会再次与关联键核对。 */
        String deviceRef
) { }
