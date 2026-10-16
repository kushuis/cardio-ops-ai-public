package io.cardioops.domain.lowbattery;

import io.cardioops.domain.device.DeviceDescriptor;

import java.time.Instant;

/** DevicePlatform 低电量患者服务记录及设备快照。 */
public record LowBatteryContext(
        /** 上游低电量患者服务记录引用，用于审计和模型引用。 */
        String evidenceRef,
        /** 当前案件绑定的设备序列号。 */
        String deviceRef,
        /** 冻结的设备型号与固件，用于选择 Device Profile。 */
        DeviceDescriptor device,
        /** DevicePlatform 观察到低电量的时间，也是近期传输查询的起点。 */
        Instant observedAt,
        /** 上游已有的低电量说明，不包含患者身份信息。 */
        String batteryMessage
) { }
