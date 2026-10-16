package io.cardioops.agent.lowbattery;

import io.cardioops.application.agentrun.AgentRunLeaseGuard;
import io.cardioops.application.agentrun.AgentRunLeaseLostException;
import io.cardioops.application.agentrun.AgentToolCallAudit;
import io.cardioops.application.agentrun.AgentToolCallStatus;
import io.cardioops.application.lowbattery.LowBatteryEvidenceScope;
import io.cardioops.application.lowbattery.LowBatteryEvidenceSource;
import io.cardioops.application.lowbattery.LowBatteryInvestigationStore;
import io.cardioops.domain.agent.AgentRun;
import io.cardioops.domain.agent.ToolCapability;
import io.cardioops.domain.incident.Incident;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** 低电量场景的本地安全网关：绑定 Incident Scope、租约、预算、证据账本与审计。 */
final class LowBatteryToolGateway {
    static final String GET_CONTEXT = "get_low_battery_context";
    static final String QUERY_TRANSMISSIONS = "query_transmissions_after_low_battery";
    static final String GET_GATEWAY = "get_low_battery_gateway_snapshot";
    private static final int MAX_ITEMS = 20;
    private static final Map<String, ToolCapability> CAPABILITIES = Map.of(
            GET_CONTEXT, ToolCapability.GET_DEVICE_CONTEXT,
            QUERY_TRANSMISSIONS, ToolCapability.GET_TRANSMISSION_HISTORY,
            GET_GATEWAY, ToolCapability.GET_GATEWAY_SNAPSHOT);

    private final AgentRun run;
    private final Incident incident;
    private final LowBatteryEvidenceSource source;
    private final LowBatteryInvestigationStore store;
    private final Clock clock;
    private final MeterRegistry meters;
    private final AgentRunLeaseGuard leaseGuard;
    private final Set<String> executed = new HashSet<>();
    private final LowBatteryEvidenceLedger ledger = new LowBatteryEvidenceLedger();

    LowBatteryToolGateway(AgentRun run, Incident incident, LowBatteryEvidenceSource source,
                          LowBatteryInvestigationStore store, Clock clock, MeterRegistry meters,
                          AgentRunLeaseGuard leaseGuard) {
        this.run = run;
        this.incident = incident;
        this.source = source;
        this.store = store;
        this.clock = clock;
        this.meters = meters;
        this.leaseGuard = leaseGuard;
    }

    List<ToolCallback> callbacks(Set<ToolCapability> allowed) {
        List<ToolCallback> result = List.of(
                callback(GET_CONTEXT, "Load the low-battery event and device context. Call this first.", () -> {
                    var value = source.getContext(scope(), run.traceId());
                    if (!value.deviceRef().equals(incident.deviceRef())) throw new GuardException("SOURCE_SCOPE_MISMATCH");
                    ledger.recordContext(value);
                    return new Execution<>(value, 1, false);
                }),
                callback(QUERY_TRANSMISSIONS, "Query completed transmissions after the low-battery event.", () -> {
                    var raw = source.listRecentTransmissions(scope(), run.traceId());
                    var value = raw.stream().limit(MAX_ITEMS).toList();
                    ledger.recordTransmissions(value);
                    return new Execution<>(value, value.size(), raw.size() > MAX_ITEMS);
                }),
                callback(GET_GATEWAY, "Load the current gateway binding and connectivity snapshot.", () -> {
                    var value = source.getCurrentGateway(scope(), run.traceId());
                    ledger.recordGateway(value);
                    return new Execution<>(value, 1, false);
                })
        ).stream().filter(callback -> allowed.contains(CAPABILITIES.get(callback.getToolDefinition().name()))).toList();
        if (result.size() != 3) throw new IllegalStateException("PROFILE_MISSING_LOW_BATTERY_TOOLS");
        return result;
    }

    static List<String> allowedToolNames(Set<ToolCapability> capabilities) {
        return CAPABILITIES.entrySet().stream().filter(e -> capabilities.contains(e.getValue()))
                .map(Map.Entry::getKey).sorted().toList();
    }

    static Set<ToolCapability> capabilitiesForToolNames(List<String> names) {
        var result = new java.util.LinkedHashSet<ToolCapability>();
        for (String name : names) {
            ToolCapability capability = CAPABILITIES.get(name);
            if (capability == null) throw new IllegalStateException("POLICY_SNAPSHOT_TOOL_UNKNOWN: " + name);
            result.add(capability);
        }
        return Set.copyOf(result);
    }

    LowBatteryEvidenceLedger ledger() { return ledger; }

    private ToolCallback callback(String name, String description, Supplier<Execution<?>> action) {
        return FunctionToolCallback.<IncidentInput, Object>builder(name,
                        input -> execute(name, input.incidentId(), action))
                .description(description).inputType(IncidentInput.class).build();
    }

    private synchronized Object execute(String toolName, String requestedIncidentId,
                                        Supplier<Execution<?>> action) {
        Instant started = clock.instant();
        String digest = digest(incident.incidentId());
        leaseGuard.assertLeaseActive();
        try {
            if (!incident.incidentId().equals(requestedIncidentId)) throw new GuardException("INCIDENT_SCOPE_VIOLATION");
            if (!executed.add(toolName)) throw new GuardException("DUPLICATE_TOOL_CALL");
            if (!GET_CONTEXT.equals(toolName) && ledger.context().isEmpty()) throw new GuardException("CONTEXT_MUST_BE_LOADED_FIRST");
            Execution<?> result = action.get();
            audit(toolName, AgentToolCallStatus.SUCCEEDED, digest, result.count(), result.truncated(), null, started);
            meters.counter("cardio_ops.agent.tool.call", "tool", toolName, "outcome", "succeeded").increment();
            leaseGuard.assertLeaseActive();
            return result.value();
        } catch (AgentRunLeaseLostException lost) {
            throw lost;
        } catch (GuardException rejected) {
            audit(toolName, AgentToolCallStatus.REJECTED, digest, 0, false, rejected.getMessage(), started);
            throw rejected;
        } catch (RuntimeException failed) {
            audit(toolName, AgentToolCallStatus.FAILED, digest, 0, false,
                    failed.getClass().getSimpleName(), started);
            throw failed;
        }
    }

    private void audit(String tool, AgentToolCallStatus status, String digest, int count,
                       boolean truncated, String error, Instant started) {
        store.appendToolCall(new AgentToolCallAudit(UUID.randomUUID().toString(), run.runId(),
                incident.incidentId(), tool, status, digest, count, truncated, error, started, clock.instant()));
    }

    private LowBatteryEvidenceScope scope() {
        return new LowBatteryEvidenceScope(incident.incidentId(), incident.correlationRef(), incident.deviceRef());
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)), 0, 8);
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private record IncidentInput(String incidentId) { }
    private record Execution<T>(T value, int count, boolean truncated) { }
    private static final class GuardException extends RuntimeException {
        private GuardException(String code) { super(code); }
    }
}
