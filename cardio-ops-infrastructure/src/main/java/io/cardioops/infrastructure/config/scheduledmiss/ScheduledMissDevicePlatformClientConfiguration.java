package io.cardioops.infrastructure.config.scheduledmiss;

import io.cardioops.application.scheduledmiss.ScheduledMissEvidenceSource;
import io.cardioops.infrastructure.integration.scheduledmiss.HttpDevicePlatformScheduledMissEvidenceClient;
import io.cardioops.infrastructure.integration.scheduledmiss.McpDevicePlatformScheduledMissEvidenceClient;
import io.cardioops.infrastructure.integration.scheduledmiss.DevicePlatformMcpClientProperties;
import io.cardioops.infrastructure.integration.scheduledmiss.DevicePlatformScheduledMissApiProperties;
import io.cardioops.application.lowbattery.LowBatteryEvidenceSource;
import io.cardioops.infrastructure.integration.lowbattery.McpDevicePlatformLowBatteryEvidenceClient;
import io.cardioops.infrastructure.integration.lowbattery.HttpDevicePlatformLowBatteryEvidenceClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpRequest;
import java.util.List;

/**
 * Agent 打开时才创建要服务令牌的证据客户端。传输写成 mcp 走 MCP，只读工具。写成 http，或者这个属性根本没配，才用 HTTP 查询。
 * 同一次调查里 MCP 失败不会自动改走 HTTP。嵌套配置自己也要带 agent.enabled，不然 Agent 关掉时仍可能去绑令牌。
 * MCP HTTP 请求显式携带 Bearer 凭证并传播当前 Trace 上下文。
 * Span 按工具阶段拆。导出同样看 CARDIO_OPS_OTEL_ENABLED，默认关。
 *
 *
 * 退回会把“这条路不可用”伪装成另一条链路的成功。要退只能是配置上显式选 http，不是故障时临场改道。
 */

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "cardio-ops.agent", name = "enabled", havingValue = "true")
public class ScheduledMissDevicePlatformClientConfiguration {

    /** 迁移期显式选择的旧 HTTP 适配器；MCP 故障时不会自动退回该路径。 */
    @Configuration(proxyBeanMethods = false)
    // 嵌套配置类可能被组件扫描直接发现，不能只依赖外层类的条件。
    @ConditionalOnProperty(prefix = "cardio-ops.agent", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(
            prefix = "cardio-ops.device-platform", name = "transport", havingValue = "http", matchIfMissing = true)
    @EnableConfigurationProperties(DevicePlatformScheduledMissApiProperties.class)
    static class HttpEvidenceSourceConfiguration {

        @Bean
        ScheduledMissEvidenceSource scheduledMissEvidenceSource(
                DevicePlatformScheduledMissApiProperties properties
        ) {
            return new HttpDevicePlatformScheduledMissEvidenceClient(properties);
        }

        @Bean
        LowBatteryEvidenceSource lowBatteryEvidenceSource(
                DevicePlatformScheduledMissApiProperties properties
        ) {
            return new HttpDevicePlatformLowBatteryEvidenceClient(properties);
        }

    }

    /** Production adapter that discovers and calls read-only Device Platform tools through MCP. */
    @Configuration(proxyBeanMethods = false)
    // Agent 被禁用时不绑定 MCP Token，也不创建任何远程 Client Adapter。
    @ConditionalOnProperty(prefix = "cardio-ops.agent", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "cardio-ops.device-platform", name = "transport", havingValue = "mcp")
    @EnableConfigurationProperties(DevicePlatformMcpClientProperties.class)
    static class McpEvidenceSourceConfiguration {

        @Bean
        ScheduledMissEvidenceSource scheduledMissEvidenceSource(
                List<McpSyncClient> clients,
                DevicePlatformMcpClientProperties properties,
                ObjectMapper objectMapper,
                ObservationRegistry observationRegistry
        ) {
            return new McpDevicePlatformScheduledMissEvidenceClient(
                    clients, properties, objectMapper, observationRegistry);
        }

        @Bean
        LowBatteryEvidenceSource lowBatteryEvidenceSource(
                List<McpSyncClient> clients,
                DevicePlatformMcpClientProperties properties,
                ObjectMapper objectMapper,
                ObservationRegistry observationRegistry
        ) {
            return new McpDevicePlatformLowBatteryEvidenceClient(
                    clients, properties, objectMapper, observationRegistry);
        }

        /**
         * Streamable HTTP 每次请求都附带服务令牌。Spring AI MCP Starter 本身不会自动认证服务端。
         */
        @Bean
        McpSyncHttpClientRequestCustomizer devicePlatformMcpAuthorizationHeader(
                DevicePlatformMcpClientProperties properties,
                Tracer tracer,
                Propagator propagator
        ) {
            return (HttpRequest.Builder builder, String method, java.net.URI uri,
                    String body, io.modelcontextprotocol.common.McpTransportContext context) -> {
                builder.header("Authorization", "Bearer " + properties.serviceToken());
                var current = tracer.currentTraceContext().context();
                if (current != null) {
                    // MCP Starter 当前使用 JDK HttpClient，不能依赖 RestClient 自动传播。
                    propagator.inject(current, builder, HttpRequest.Builder::header);
                }
            };
        }
    }
}
