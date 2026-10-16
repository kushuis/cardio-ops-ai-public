package io.cardioops.agent.lowbattery;

import io.cardioops.domain.agent.*;
import io.cardioops.domain.device.*;
import io.cardioops.domain.incident.*;
import io.cardioops.domain.lowbattery.*;
import io.cardioops.domain.scheduledmiss.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LowBatteryResultValidatorTest {
    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
    private final LowBatteryResultValidator validator = new LowBatteryResultValidator(
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test void noTransmissionMeansInsufficientEvidence() {
        assertThat(validate(List.of()).impactAssessment())
                .isEqualTo(LowBatteryImpactAssessment.INSUFFICIENT_EVIDENCE);
    }
    @Test void latestCompleteTransmissionMeansNoObservedImpact() {
        var result = validate(List.of(transmission(10, 10)));
        assertThat(result.impactAssessment()).isEqualTo(LowBatteryImpactAssessment.NO_OBSERVED_IMPACT);
        assertThat(result.evidenceRefs()).containsExactly("battery:1");
    }
    @Test void latestIncompleteTransmissionMeansPossibleImpact() {
        assertThat(validate(List.of(transmission(10, 6))).impactAssessment())
                .isEqualTo(LowBatteryImpactAssessment.POSSIBLE_IMPACT);
    }
    @Test void evidenceLedgerDistinguishesEmptyQueryFromMissingToolCall() {
        var ledger = new LowBatteryEvidenceLedger();
        ledger.recordContext(context());
        ledger.recordGateway(gateway());
        assertThatThrownBy(ledger::snapshot).hasMessage("LOW_BATTERY_REQUIRED_EVIDENCE_MISSING");

        ledger.recordTransmissions(List.of());
        assertThat(ledger.snapshot().transmissions()).isEmpty();
    }

    private LowBatteryInvestigationResult validate(List<LowBatteryTransmissionObservation> transmissions) {
        var output = new LowBatteryAgentOutput(
                new LowBatteryModelDraft(List.of(), List.of(), List.of(), List.of(),
                        List.of("battery:1", "invented"), "summary"),
                new CollectedLowBatteryEvidence(context(), transmissions, gateway()));
        return validator.validate(run(), incident(), output);
    }
    private LowBatteryContext context() {
        return new LowBatteryContext("battery:1", "device-1",
                new DeviceDescriptor(DeviceFamily.ICM, "DEMO_ICM_V2_SECURE", "1.1.0.0", 17408),
                NOW.minusSeconds(3600), "low");
    }
    private GatewayStatusSnapshot gateway() {
        return new GatewayStatusSnapshot("gateway:1", GatewayConnectivity.ONLINE, NOW, NOW, null);
    }
    private LowBatteryTransmissionObservation transmission(int total, int received) {
        return new LowBatteryTransmissionObservation("sample:1", "GATEWAY", NOW, total, received);
    }
    private AgentRun run() {
        return new AgentRun("run-1", "incident-1", "event-1", "thread-1", AgentRunStatus.RUNNING,
                "demo-icm-v2-secure", "trace-1", 1, "worker", "token", NOW.plusSeconds(60), NOW, NOW);
    }
    private Incident incident() {
        return new Incident("incident-1", "key-1", "device-1",
                new DeviceDescriptor(DeviceFamily.ICM, "DEMO_ICM_V2_SECURE", "1.1.0.0", 17408),
                IncidentType.DEVICE_LOW_BATTERY, IncidentSeverity.UNASSESSED, IncidentStatus.DETECTED,
                "BATTERY:device-1:ICM_PATIENT_SERVICE:1", NOW, NOW,
                ProfileResolutionStatus.SUPPORTED, "demo-icm-v2-secure", 0, NOW, NOW, null);
    }
}
