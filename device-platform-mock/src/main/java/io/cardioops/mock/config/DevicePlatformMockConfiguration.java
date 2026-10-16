package io.cardioops.mock.config;

import io.cardioops.mock.tool.DevicePlatformEvidenceTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Exposes synthetic evidence through the same governed MCP boundary used in deployment. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DevicePlatformMockProperties.class)
public class DevicePlatformMockConfiguration {

    @Bean
    DevicePlatformEvidenceTools devicePlatformEvidenceTools(DevicePlatformMockProperties properties) {
        return new DevicePlatformEvidenceTools(properties.clientRole());
    }

    @Bean
    ToolCallbackProvider evidenceToolCallbacks(DevicePlatformEvidenceTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }

    @Bean
    FilterRegistrationBean<McpEndpointAuthenticationFilter> mcpAuthenticationFilter(
            DevicePlatformMockProperties properties
    ) {
        var registration = new FilterRegistrationBean<>(
                new McpEndpointAuthenticationFilter(properties.serviceToken()));
        registration.setName("mcpEndpointAuthenticationFilter");
        registration.addUrlPatterns("/mcp", "/mcp/*");
        registration.setOrder(Integer.MIN_VALUE);
        return registration;
    }
}
