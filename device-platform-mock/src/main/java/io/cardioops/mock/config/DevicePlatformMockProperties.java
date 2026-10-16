package io.cardioops.mock.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Service identity bound to the public demo MCP endpoint. */
@ConfigurationProperties("device-platform.mock")
public record DevicePlatformMockProperties(String serviceToken, String clientRole) {

    public DevicePlatformMockProperties {
        if (serviceToken == null || serviceToken.isBlank()) {
            throw new IllegalArgumentException("device-platform.mock.service-token must not be blank");
        }
        if (!"SYSTEM_WORKER".equals(clientRole)) {
            throw new IllegalArgumentException("device-platform.mock.client-role must be SYSTEM_WORKER");
        }
    }
}
