package io.cardioops.agent.lowbattery;

import io.cardioops.domain.lowbattery.LowBatteryContext;
import io.cardioops.domain.lowbattery.LowBatteryTransmissionObservation;
import io.cardioops.domain.scheduledmiss.GatewayStatusSnapshot;

import java.util.List;

/** 本次低电量调查真正通过工具拿到的证据账本。 */
public record CollectedLowBatteryEvidence(
        /** 精确绑定本案件的低电量记录与设备快照。 */
        LowBatteryContext context,
        /** 低电量发生后查询到的最近传输，允许为空但工具必须执行过。 */
        List<LowBatteryTransmissionObservation> transmissions,
        /** 调查当下的网关状态，只作为竞争原因。 */
        GatewayStatusSnapshot gateway
) { }
