package io.cardioops.infrastructure.integration.scheduledmiss;

import io.cardioops.application.scheduledmiss.ScheduledMissEvidenceScope;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpDevicePlatformScheduledMissEvidenceClientTest {

    private static final String DEVICE = "E1BB49E78C86";
    private static final ScheduledMissEvidenceScope SCOPE = new ScheduledMissEvidenceScope(
            "incident-1", "MONITORING:" + DEVICE + ":1788753600", DEVICE);

    @Test
    void shouldDiscoverToolsAndMapStructuredContext() {
        McpSyncClient mcp = mcpClientWithRequiredTools();
        when(mcp.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(), false, contextPayload(DEVICE), Map.of()));
        var client = new McpDevicePlatformScheduledMissEvidenceClient(
                List.of(mcp), properties(), JsonMapper.builder().findAndAddModules().build());

        var result = client.getScheduledMissContext(SCOPE, "trace-1");

        assertThat(result.incidentId()).isEqualTo("incident-1");
        assertThat(result.deviceRef()).isEqualTo(DEVICE);
        assertThat(result.device().modelCode()).isEqualTo("DEMO_ICM_V2_SECURE");
        verify(mcp).listTools();
        verify(mcp).callTool(any(McpSchema.CallToolRequest.class));
    }

    @Test
    void shouldFailClosedWhenRequiredToolWasNotDiscovered() {
        McpSyncClient mcp = mock(McpSyncClient.class);
        when(mcp.getServerInfo()).thenReturn(new McpSchema.Implementation("device-platform", "1.0.0"));
        when(mcp.listTools()).thenReturn(new McpSchema.ListToolsResult(
                List.of(tool("get_scheduled_miss_context")), null));
        when(mcp.listResources()).thenReturn(new McpSchema.ListResourcesResult(
                List.of(resource()), null));
        var client = new McpDevicePlatformScheduledMissEvidenceClient(
                List.of(mcp), properties(), JsonMapper.builder().findAndAddModules().build());

        assertThatThrownBy(() -> client.getScheduledMissContext(SCOPE, "trace-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DEVICE_PLATFORM_MCP_REQUIRED_TOOLS_MISSING");
    }

    @Test
    void shouldFailClosedWhenEvidenceContractResourceWasNotDiscovered() {
        McpSyncClient mcp = mcpClientWithRequiredTools();
        when(mcp.listResources()).thenReturn(new McpSchema.ListResourcesResult(List.of(), null));
        var client = new McpDevicePlatformScheduledMissEvidenceClient(
                List.of(mcp), properties(), JsonMapper.builder().findAndAddModules().build());

        assertThatThrownBy(() -> client.getScheduledMissContext(SCOPE, "trace-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DEVICE_PLATFORM_MCP_EVIDENCE_CONTRACT_MISSING");
    }

    @Test
    void shouldRejectDeviceReturnedOutsideIncidentScope() {
        McpSyncClient mcp = mcpClientWithRequiredTools();
        when(mcp.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(), false, contextPayload("OTHER_DEVICE"), Map.of()));
        var client = new McpDevicePlatformScheduledMissEvidenceClient(
                List.of(mcp), properties(), JsonMapper.builder().findAndAddModules().build());

        assertThatThrownBy(() -> client.getScheduledMissContext(SCOPE, "trace-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MCP_DEVICE_PLATFORM_SCOPE_MISMATCH");
    }

    private McpSyncClient mcpClientWithRequiredTools() {
        McpSyncClient mcp = mock(McpSyncClient.class);
        when(mcp.getServerInfo()).thenReturn(new McpSchema.Implementation("device-platform", "1.0.0"));
        when(mcp.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(
                tool("get_scheduled_miss_context"),
                tool("query_transmissions_in_window"),
                tool("get_current_gateway_snapshot"),
                tool("query_schedule_change_history")), null));
        when(mcp.listResources()).thenReturn(new McpSchema.ListResourcesResult(
                List.of(resource()), null));
        return mcp;
    }

    private McpSchema.Tool tool(String name) {
        return McpSchema.Tool.builder(name).inputSchema(Map.of("type", "object")).build();
    }

    private McpSchema.Resource resource() {
        return McpSchema.Resource.builder(
                        "device-platform://cardio-ops/evidence-contract/scheduled-miss/v1",
                        "scheduled-miss-evidence-contract-v1")
                .mimeType("application/json")
                .build();
    }

    private DevicePlatformMcpClientProperties properties() {
        return new DevicePlatformMcpClientProperties("device-platform", "test-token", "SYSTEM_WORKER");
    }

    private Map<String, Object> contextPayload(String deviceRef) {
        return Map.ofEntries(
                Map.entry("evidenceRef", "ICM_PATIENT_SERVICE:190"),
                Map.entry("deviceRef", deviceRef),
                Map.entry("device", Map.of(
                        "family", "ICM",
                        "modelCode", "DEMO_ICM_V2_SECURE",
                        "firmwareVersion", "1.1.0.0",
                        "firmwareVersionNumber", 17408L)),
                Map.entry("expectedTransmissionAt", "2026-10-06T04:00:00Z"),
                Map.entry("nextExpectedTransmissionAt", "2026-10-07T04:00:00Z"),
                Map.entry("gatewayAtExpected", "ONLINE"),
                Map.entry("latestBatteryLow", false));
    }
}
