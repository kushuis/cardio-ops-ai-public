package io.cardioops.agent.lowbattery;

import io.cardioops.domain.lowbattery.LowBatteryContext;
import io.cardioops.domain.lowbattery.LowBatteryTransmissionObservation;
import io.cardioops.domain.scheduledmiss.GatewayStatusSnapshot;

import java.util.List;
import java.util.Optional;

final class LowBatteryEvidenceLedger {
    private LowBatteryContext context;
    private List<LowBatteryTransmissionObservation> transmissions = List.of();
    private boolean transmissionsLoaded;
    private GatewayStatusSnapshot gateway;

    void recordContext(LowBatteryContext value) { context = value; }
    void recordTransmissions(List<LowBatteryTransmissionObservation> value) {
        transmissions = List.copyOf(value);
        transmissionsLoaded = true;
    }
    void recordGateway(GatewayStatusSnapshot value) { gateway = value; }
    Optional<LowBatteryContext> context() { return Optional.ofNullable(context); }

    CollectedLowBatteryEvidence snapshot() {
        if (context == null || !transmissionsLoaded || gateway == null) {
            throw new IllegalStateException("LOW_BATTERY_REQUIRED_EVIDENCE_MISSING");
        }
        return new CollectedLowBatteryEvidence(context, transmissions, gateway);
    }
}
