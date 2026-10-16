package io.cardioops.infrastructure.integration.lowbattery;

import io.cardioops.application.lowbattery.*;
import io.cardioops.domain.lowbattery.*;
import io.cardioops.domain.scheduledmiss.GatewayStatusSnapshot;
import io.cardioops.infrastructure.integration.scheduledmiss.DevicePlatformMcpClientProperties;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/** 通过 DevicePlatform MCP 的三个低电量只读工具收集证据。 */
public final class McpDevicePlatformLowBatteryEvidenceClient implements LowBatteryEvidenceSource {
    private static final String CONTEXT = "get_low_battery_context";
    private static final String TRANSMISSIONS = "query_transmissions_after_low_battery";
    private static final String GATEWAY = "get_low_battery_gateway_snapshot";
    private static final String CONTRACT = "device-platform://cardio-ops/evidence-contract/low-battery/v1";
    private static final Set<String> REQUIRED = Set.of(CONTEXT, TRANSMISSIONS, GATEWAY);
    private final List<McpSyncClient> clients;
    private final DevicePlatformMcpClientProperties properties;
    private final ObjectMapper json;
    private final ObservationRegistry observations;
    private volatile McpSyncClient selected;
    private volatile Set<String> tools = Set.of();

    public McpDevicePlatformLowBatteryEvidenceClient(List<McpSyncClient> clients,
            DevicePlatformMcpClientProperties properties, ObjectMapper json, ObservationRegistry observations) {
        this.clients = List.copyOf(clients); this.properties = properties; this.json = json; this.observations = observations;
    }
    @Override public LowBatteryContext getContext(LowBatteryEvidenceScope scope, String traceId) {
        return call(CONTEXT, scope, traceId, LowBatteryContext.class);
    }
    @Override public List<LowBatteryTransmissionObservation> listRecentTransmissions(
            LowBatteryEvidenceScope scope, String traceId) {
        List<McpLowBatteryTransmissionObservation> values = call(
                TRANSMISSIONS, scope, traceId, new TypeReference<>() { });
        return values.stream().map(value -> new LowBatteryTransmissionObservation(
                value.evidenceRef(), value.channel(), value.completedAt(),
                value.totalEventCount() == null ? 0 : value.totalEventCount(),
                value.receivedEventCount() == null ? 0 : value.receivedEventCount()))
                .toList();
    }
    @Override public GatewayStatusSnapshot getCurrentGateway(LowBatteryEvidenceScope scope, String traceId) {
        return call(GATEWAY, scope, traceId, GatewayStatusSnapshot.class);
    }

    private <T> T call(String tool, LowBatteryEvidenceScope scope, String traceId, Class<T> type) {
        McpSchema.CallToolResult result = invoke(tool, scope, traceId);
        if (result.structuredContent() != null) return json.convertValue(result.structuredContent(), type);
        return json.readValue(text(result), type);
    }
    private <T> T call(String tool, LowBatteryEvidenceScope scope, String traceId, TypeReference<T> type) {
        McpSchema.CallToolResult result = invoke(tool, scope, traceId);
        if (result.structuredContent() != null) return json.convertValue(result.structuredContent(), type);
        return json.readValue(text(result), type);
    }
    private McpSchema.CallToolResult invoke(String tool, LowBatteryEvidenceScope scope, String traceId) {
        McpSyncClient client = client();
        if (!tools.contains(tool)) throw new IllegalStateException("DEVICE_PLATFORM_MCP_TOOL_NOT_DISCOVERED:" + tool);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("incidentId", scope.incidentId()); args.put("correlationRef", scope.correlationRef());
        args.put("deviceRef", scope.deviceRef()); args.put("traceId", traceId);
        args.put("actorRole", properties.actorRole());
        var result = Observation.createNotStarted("mcp.tool.call", observations)
                .contextualName("device-platform " + tool).lowCardinalityKeyValue("mcp.server", properties.serverName())
                .lowCardinalityKeyValue("mcp.tool.name", tool)
                .highCardinalityKeyValue("cardio.incident.id", scope.incidentId())
                .observe(() -> client.callTool(new McpSchema.CallToolRequest(tool, args)));
        if (result == null || Boolean.TRUE.equals(result.isError()))
            throw new IllegalStateException("DEVICE_PLATFORM_MCP_TOOL_FAILED:" + tool);
        return result;
    }
    private McpSyncClient client() {
        if (selected != null) return selected;
        synchronized (this) {
            if (selected != null) return selected;
            var found = clients.stream().filter(c -> c.getServerInfo() != null)
                    .filter(c -> properties.serverName().equals(c.getServerInfo().name())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("DEVICE_PLATFORM_MCP_SERVER_NOT_AVAILABLE"));
            Set<String> discovered = found.listTools().tools().stream().map(McpSchema.Tool::name)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!discovered.containsAll(REQUIRED)) throw new IllegalStateException("DEVICE_PLATFORM_MCP_LOW_BATTERY_TOOLS_MISSING");
            boolean contract = found.listResources().resources().stream().map(McpSchema.Resource::uri).anyMatch(CONTRACT::equals);
            if (!contract) throw new IllegalStateException("DEVICE_PLATFORM_MCP_LOW_BATTERY_CONTRACT_MISSING");
            tools = discovered; selected = found; return found;
        }
    }
    private String text(McpSchema.CallToolResult result) {
        return result.content().stream().filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast).map(McpSchema.TextContent::text).findFirst()
                .orElseThrow(() -> new IllegalStateException("DEVICE_PLATFORM_MCP_EMPTY_TOOL_RESULT"));
    }

    /** 与 DevicePlatform MCP 的完整传输契约对齐，再收窄为低电量领域真正使用的字段。 */
    private record McpLowBatteryTransmissionObservation(
            String evidenceRef,
            String channel,
            Integer rawTriggerType,
            java.time.Instant startedAt,
            java.time.Instant completedAt,
            Integer totalEventCount,
            Integer receivedEventCount,
            java.time.Instant deviceReportedNextBroadcastAt
    ) { }
}
