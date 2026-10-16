package io.cardioops.application.lowbattery;

import io.cardioops.domain.lowbattery.LowBatteryContext;
import io.cardioops.domain.lowbattery.LowBatteryTransmissionObservation;
import io.cardioops.domain.scheduledmiss.GatewayStatusSnapshot;

import java.util.List;

/** DevicePlatform 低电量调查的只读证据端口；生产实现通过 MCP。 */
public interface LowBatteryEvidenceSource {
    LowBatteryContext getContext(LowBatteryEvidenceScope scope, String traceId);
    List<LowBatteryTransmissionObservation> listRecentTransmissions(LowBatteryEvidenceScope scope, String traceId);
    GatewayStatusSnapshot getCurrentGateway(LowBatteryEvidenceScope scope, String traceId);
}
