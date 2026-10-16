package io.cardioops.infrastructure.integration.scheduledmiss;

import io.cardioops.application.scheduledmiss.ScheduledMissEvidenceScope;
import io.cardioops.application.scheduledmiss.ScheduledMissEvidenceSource;
import io.cardioops.application.scheduledmiss.DevicePlatformTransmissionObservation;
import io.cardioops.domain.device.DeviceDescriptor;
import io.cardioops.domain.scheduledmiss.GatewayStatusSnapshot;
import io.cardioops.domain.scheduledmiss.ScheduleChangeObservation;
import io.cardioops.domain.scheduledmiss.ScheduledMissContext;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生产用的证据源。四个远程工具都是只读事实：上下文、传输窗口、当前网关、改期历史。
 * 知识检索不在这里，它留在本地。模型也到不了这个类，它只碰到网关上的那层外壳。
 *
 * <p>actorRole 从配置放进每次调用，而且配置只接受 SYSTEM_WORKER。
 * 模型的入参里没有角色字段，所以它报不了另一个角色。Server 认的是这个服务身份。</p>
 *
 * <p>第一次调用才做能力协商。服务器不在、工具缺了、证据契约资源不在，这次调查就失败。
 * 不会悄悄改走 HTTP。改走 HTTP 等于绕过刚刚谈好的 MCP 边界。</p>
 *
 * <p>每次 callTool 一个 Span，名字是 mcp.tool.call，按工具阶段拆开，不跟整次 Run 挤在同一个 Span 里。
 * incidentId 只进 Trace。要不要导出看 management.opentelemetry.enabled，
 * 也就是 CARDIO_OPS_OTEL_ENABLED，默认关着。</p>
 *
 * <p>
 * 租约、档案、预算、证据账本和审计都在网关。这个类只换远程传输。让模型直接 callTool，这些门就没了。</p>
 */
public final class McpDevicePlatformScheduledMissEvidenceClient implements ScheduledMissEvidenceSource {

    private static final String GET_CONTEXT = "get_scheduled_miss_context";
    private static final String QUERY_TRANSMISSIONS = "query_transmissions_in_window";
    private static final String GET_CURRENT_GATEWAY = "get_current_gateway_snapshot";
    private static final String QUERY_SCHEDULE_CHANGES = "query_schedule_change_history";
    private static final String EVIDENCE_CONTRACT_RESOURCE =
            "device-platform://cardio-ops/evidence-contract/scheduled-miss/v1";
    private static final Set<String> REQUIRED_TOOLS = Set.of(
            GET_CONTEXT, QUERY_TRANSMISSIONS, GET_CURRENT_GATEWAY, QUERY_SCHEDULE_CHANGES);
    private static final Logger log = LoggerFactory.getLogger(McpDevicePlatformScheduledMissEvidenceClient.class);

    private final List<McpSyncClient> clients;
    private final DevicePlatformMcpClientProperties properties;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;
    private volatile McpSyncClient selectedClient;
    private volatile Set<String> discoveredTools = Set.of();

    public McpDevicePlatformScheduledMissEvidenceClient(
            List<McpSyncClient> clients,
            DevicePlatformMcpClientProperties properties,
            ObjectMapper objectMapper
    ) {
        this(clients, properties, objectMapper, ObservationRegistry.NOOP);
    }

    public McpDevicePlatformScheduledMissEvidenceClient(
            List<McpSyncClient> clients,
            DevicePlatformMcpClientProperties properties,
            ObjectMapper objectMapper,
            ObservationRegistry observationRegistry
    ) {
        this.clients = List.copyOf(clients);
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public ScheduledMissContext getScheduledMissContext(ScheduledMissEvidenceScope scope, String traceId) {
        McpScheduledMissContext remote = call(GET_CONTEXT, scope, traceId, McpScheduledMissContext.class);
        // 远端事实不携带本地 incidentId；设备引用不一致时拒绝接纳，防止跨设备证据污染。
        if (!scope.deviceRef().equals(remote.deviceRef())) {
            throw new IllegalStateException("MCP_DEVICE_PLATFORM_SCOPE_MISMATCH");
        }
        return new ScheduledMissContext(
                remote.evidenceRef(), scope.incidentId(), remote.deviceRef(), remote.device(),
                remote.expectedTransmissionAt(), remote.nextExpectedTransmissionAt(),
                remote.gatewayAtExpected(), remote.latestBatteryLow(), remote.batteryObservedAt(),
                remote.lastDeviceReportedBroadcastAt(), remote.deviceBroadcastObservedAt());
    }

    @Override
    public List<DevicePlatformTransmissionObservation> queryTransmissionsInWindow(
            ScheduledMissEvidenceScope scope, String traceId) {
        return call(QUERY_TRANSMISSIONS, scope, traceId, new TypeReference<>() { });
    }

    @Override
    public GatewayStatusSnapshot getCurrentGatewaySnapshot(ScheduledMissEvidenceScope scope, String traceId) {
        return call(GET_CURRENT_GATEWAY, scope, traceId, GatewayStatusSnapshot.class);
    }

    @Override
    public List<ScheduleChangeObservation> queryScheduleChangeHistory(
            ScheduledMissEvidenceScope scope, String traceId) {
        return call(QUERY_SCHEDULE_CHANGES, scope, traceId, new TypeReference<>() { });
    }

    private <T> T call(
            String toolName,
            ScheduledMissEvidenceScope scope,
            String traceId,
            Class<T> responseType
    ) {
        McpSchema.CallToolResult result = invoke(toolName, scope, traceId);
        Object structured = result.structuredContent();
        if (structured != null) {
            return objectMapper.convertValue(structured, responseType);
        }
        return readText(result, text -> objectMapper.readValue(text, responseType));
    }

    private <T> T call(
            String toolName,
            ScheduledMissEvidenceScope scope,
            String traceId,
            TypeReference<T> responseType
    ) {
        McpSchema.CallToolResult result = invoke(toolName, scope, traceId);
        Object structured = result.structuredContent();
        if (structured != null) {
            return objectMapper.convertValue(structured, responseType);
        }
        return readText(result, text -> objectMapper.readValue(text, responseType));
    }

    private McpSchema.CallToolResult invoke(
            String toolName,
            ScheduledMissEvidenceScope scope,
            String traceId
    ) {
        McpSyncClient client = client();
        if (!discoveredTools.contains(toolName)) {
            throw new IllegalStateException("DEVICE_PLATFORM_MCP_TOOL_NOT_DISCOVERED:" + toolName);
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("incidentId", scope.incidentId());
        arguments.put("correlationRef", scope.correlationRef());
        arguments.put("deviceRef", scope.deviceRef());
        arguments.put("traceId", traceId);
        // 角色来自配置里的 SYSTEM_WORKER，不来自调用方临时传入的字符串。
        arguments.put("actorRole", properties.actorRole());

        log.debug("DevicePlatform MCP tool call started incidentId={} toolName={}", scope.incidentId(), toolName);
        McpSchema.CallToolResult result = Observation.createNotStarted("mcp.tool.call", observationRegistry)
                .contextualName("device-platform " + toolName)
                .lowCardinalityKeyValue("mcp.server", properties.serverName())
                .lowCardinalityKeyValue("mcp.tool.name", toolName)
                .highCardinalityKeyValue("cardio.incident.id", scope.incidentId())
                .highCardinalityKeyValue("cardio.correlation.id", traceId)
                .observe(() -> client.callTool(new McpSchema.CallToolRequest(toolName, arguments)));
        if (result == null || Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException("DEVICE_PLATFORM_MCP_TOOL_FAILED:" + toolName);
        }
        return result;
    }

    /**
     * 按服务器名挑连接，再核对四个只读工具和证据契约资源都在。
     * 缺任何一个就失败。协商结果留在内存里，同一次进程后面的调用不再问一遍。
     *
     * <p>
     * 退回就把“MCP 不可用”伪装成另一条链路的成功。这单应该失败，让租约和重试去处理，而不是换一条没谈过的路。</p>
     */
    private McpSyncClient client() {
        McpSyncClient current = selectedClient;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (selectedClient != null) {
                return selectedClient;
            }
            McpSyncClient found = clients.stream()
                    .filter(client -> client.getServerInfo() != null)
                    .filter(client -> properties.serverName().equals(client.getServerInfo().name()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "DEVICE_PLATFORM_MCP_SERVER_NOT_AVAILABLE:" + properties.serverName()));
            Set<String> tools = found.listTools().tools().stream()
                    .map(McpSchema.Tool::name)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!tools.containsAll(REQUIRED_TOOLS)) {
                throw new IllegalStateException("DEVICE_PLATFORM_MCP_REQUIRED_TOOLS_MISSING");
            }
            Set<String> resources = found.listResources().resources().stream()
                    .map(McpSchema.Resource::uri)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!resources.contains(EVIDENCE_CONTRACT_RESOURCE)) {
                throw new IllegalStateException("DEVICE_PLATFORM_MCP_EVIDENCE_CONTRACT_MISSING");
            }
            discoveredTools = tools;
            selectedClient = found;
            log.info("DevicePlatform MCP capabilities discovered serverName={} tools={} resources={}",
                    properties.serverName(), tools.stream().sorted().toList(), resources.stream().sorted().toList());
            return found;
        }
    }

    private <T> T readText(McpSchema.CallToolResult result, JsonReader<T> reader) {
        String text = result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("DEVICE_PLATFORM_MCP_EMPTY_TOOL_RESULT"));
        try {
            return reader.read(text);
        } catch (Exception parseFailure) {
            throw new IllegalStateException("DEVICE_PLATFORM_MCP_RESULT_PARSE_FAILED", parseFailure);
        }
    }

    @FunctionalInterface
    private interface JsonReader<T> {
        T read(String text) throws Exception;
    }

    /** 远端上下文没有本地 incidentId。incidentId 用这次调用带去的 scope 填回去。 */
    private record McpScheduledMissContext(
            String evidenceRef,
            String deviceRef,
            DeviceDescriptor device,
            java.time.Instant expectedTransmissionAt,
            java.time.Instant nextExpectedTransmissionAt,
            io.cardioops.domain.scheduledmiss.GatewayConnectivity gatewayAtExpected,
            boolean latestBatteryLow,
            java.time.Instant batteryObservedAt,
            java.time.Instant lastDeviceReportedBroadcastAt,
            java.time.Instant deviceBroadcastObservedAt
    ) { }
}
