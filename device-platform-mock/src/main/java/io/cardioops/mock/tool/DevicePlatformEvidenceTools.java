package io.cardioops.mock.tool;

import io.cardioops.mock.contract.EvidenceContracts;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Read-only MCP tools backed exclusively by deterministic synthetic data.
 * No patient identity, raw ECG sample or production identifier is present in this module.
 */
public final class DevicePlatformEvidenceTools {

    private final InvestigationScopeValidator validator;

    public DevicePlatformEvidenceTools(String authenticatedRole) {
        validator = new InvestigationScopeValidator(authenticatedRole);
    }

    @Tool(name = "get_scheduled_miss_context", description = "Load synthetic scheduled-miss facts. Read-only; call first.")
    public EvidenceContracts.ScheduledMissContext scheduledMissContext(
            @ToolParam(description = "CardioOps incident ID", required = true) String incidentId,
            @ToolParam(description = "MONITORING correlation reference", required = true) String correlationRef,
            @ToolParam(description = "Incident-bound device reference", required = true) String deviceRef,
            @ToolParam(description = "End-to-end trace ID", required = true) String traceId,
            @ToolParam(description = "Trusted caller role", required = true) String actorRole
    ) {
        var scope = validator.scheduledMiss(scope(incidentId, correlationRef, deviceRef, traceId, actorRole));
        Instant expectedAt = Instant.ofEpochSecond(Long.parseLong(scope.correlationRef().substring(
                scope.correlationRef().lastIndexOf(':') + 1)));
        return new EvidenceContracts.ScheduledMissContext(
                "ctx-" + deviceRef, deviceRef, demoDevice(), expectedAt,
                expectedAt.plus(1, ChronoUnit.DAYS), EvidenceContracts.GatewayConnectivity.OFFLINE,
                false, null, expectedAt.minus(1, ChronoUnit.DAYS), expectedAt.minus(1, ChronoUnit.DAYS));
    }

    @Tool(name = "query_transmissions_in_window", description = "Query synthetic transmission batches in the scoped window.")
    public List<EvidenceContracts.TransmissionObservation> scheduledMissTransmissions(
            @ToolParam(description = "CardioOps incident ID", required = true) String incidentId,
            @ToolParam(description = "MONITORING correlation reference", required = true) String correlationRef,
            @ToolParam(description = "Incident-bound device reference", required = true) String deviceRef,
            @ToolParam(description = "End-to-end trace ID", required = true) String traceId,
            @ToolParam(description = "Trusted caller role", required = true) String actorRole
    ) {
        var scope = validator.scheduledMiss(scope(incidentId, correlationRef, deviceRef, traceId, actorRole));
        Instant expectedAt = expectedAt(scope);
        return List.of(new EvidenceContracts.TransmissionObservation(
                "tx-recovery-" + deviceRef, EvidenceContracts.EvidenceUploadChannel.GATEWAY, 3,
                expectedAt.plus(8, ChronoUnit.MINUTES), expectedAt.plus(10, ChronoUnit.MINUTES),
                4, 4, expectedAt.plus(1, ChronoUnit.DAYS)));
    }

    @Tool(name = "get_current_gateway_snapshot", description = "Load current synthetic gateway connectivity.")
    public EvidenceContracts.GatewaySnapshot scheduledMissGateway(
            @ToolParam(description = "CardioOps incident ID", required = true) String incidentId,
            @ToolParam(description = "MONITORING correlation reference", required = true) String correlationRef,
            @ToolParam(description = "Incident-bound device reference", required = true) String deviceRef,
            @ToolParam(description = "End-to-end trace ID", required = true) String traceId,
            @ToolParam(description = "Trusted caller role", required = true) String actorRole
    ) {
        var scope = validator.scheduledMiss(scope(incidentId, correlationRef, deviceRef, traceId, actorRole));
        Instant observedAt = expectedAt(scope).plus(11, ChronoUnit.MINUTES);
        return gateway(deviceRef, observedAt);
    }

    @Tool(name = "query_schedule_change_history", description = "Query synthetic schedule changes for the scoped window.")
    public List<EvidenceContracts.ScheduleChangeObservation> scheduleChanges(
            @ToolParam(description = "CardioOps incident ID", required = true) String incidentId,
            @ToolParam(description = "MONITORING correlation reference", required = true) String correlationRef,
            @ToolParam(description = "Incident-bound device reference", required = true) String deviceRef,
            @ToolParam(description = "End-to-end trace ID", required = true) String traceId,
            @ToolParam(description = "Trusted caller role", required = true) String actorRole
    ) {
        validator.scheduledMiss(scope(incidentId, correlationRef, deviceRef, traceId, actorRole));
        return List.of();
    }

    @Tool(name = "get_low_battery_context", description = "Load synthetic low-battery facts. Read-only.")
    public EvidenceContracts.LowBatteryContext lowBatteryContext(
            @ToolParam(description = "CardioOps incident ID", required = true) String incidentId,
            @ToolParam(description = "BATTERY correlation reference", required = true) String correlationRef,
            @ToolParam(description = "Incident-bound device reference", required = true) String deviceRef,
            @ToolParam(description = "End-to-end trace ID", required = true) String traceId,
            @ToolParam(description = "Trusted caller role", required = true) String actorRole
    ) {
        validator.lowBattery(scope(incidentId, correlationRef, deviceRef, traceId, actorRole));
        return new EvidenceContracts.LowBatteryContext(
                "battery-demo-" + deviceRef, deviceRef, demoDevice(),
                Instant.parse("2026-01-15T08:00:00Z"), "Synthetic low-battery alert; no patient identity included.");
    }

    @Tool(name = "query_transmissions_after_low_battery", description = "Query completed synthetic transmissions after the alert.")
    public List<EvidenceContracts.TransmissionObservation> lowBatteryTransmissions(
            @ToolParam(description = "CardioOps incident ID", required = true) String incidentId,
            @ToolParam(description = "BATTERY correlation reference", required = true) String correlationRef,
            @ToolParam(description = "Incident-bound device reference", required = true) String deviceRef,
            @ToolParam(description = "End-to-end trace ID", required = true) String traceId,
            @ToolParam(description = "Trusted caller role", required = true) String actorRole
    ) {
        validator.lowBattery(scope(incidentId, correlationRef, deviceRef, traceId, actorRole));
        Instant startedAt = Instant.parse("2026-01-15T09:00:00Z");
        return List.of(new EvidenceContracts.TransmissionObservation(
                "tx-after-battery-" + deviceRef, EvidenceContracts.EvidenceUploadChannel.GATEWAY, 3,
                startedAt, startedAt.plus(2, ChronoUnit.MINUTES), 3, 3,
                startedAt.plus(1, ChronoUnit.DAYS)));
    }

    @Tool(name = "get_low_battery_gateway_snapshot", description = "Load current synthetic gateway connectivity.")
    public EvidenceContracts.GatewaySnapshot lowBatteryGateway(
            @ToolParam(description = "CardioOps incident ID", required = true) String incidentId,
            @ToolParam(description = "BATTERY correlation reference", required = true) String correlationRef,
            @ToolParam(description = "Incident-bound device reference", required = true) String deviceRef,
            @ToolParam(description = "End-to-end trace ID", required = true) String traceId,
            @ToolParam(description = "Trusted caller role", required = true) String actorRole
    ) {
        validator.lowBattery(scope(incidentId, correlationRef, deviceRef, traceId, actorRole));
        return gateway(deviceRef, Instant.parse("2026-01-15T09:03:00Z"));
    }

    private InvestigationScope scope(
            String incidentId, String correlationRef, String deviceRef, String traceId, String actorRole) {
        return new InvestigationScope(incidentId, correlationRef, deviceRef, traceId, actorRole);
    }

    private Instant expectedAt(InvestigationScope scope) {
        return Instant.ofEpochSecond(Long.parseLong(
                scope.correlationRef().substring(scope.correlationRef().lastIndexOf(':') + 1)));
    }

    private EvidenceContracts.DeviceDescriptor demoDevice() {
        return new EvidenceContracts.DeviceDescriptor(
                EvidenceContracts.DeviceFamily.ICM, "DEMO_ICM_V2_SECURE", "1.1.0.0", 17408L);
    }

    private EvidenceContracts.GatewaySnapshot gateway(String deviceRef, Instant observedAt) {
        return new EvidenceContracts.GatewaySnapshot(
                "gateway-" + deviceRef, EvidenceContracts.GatewayConnectivity.ONLINE,
                observedAt, observedAt.minus(1, ChronoUnit.MINUTES), observedAt.minus(2, ChronoUnit.DAYS));
    }
}
