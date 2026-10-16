package io.cardioops.infrastructure.integration.scheduledmiss;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * HTTP 查询用的内网地址、服务令牌和超时。令牌不写进代码。地址不应该指到公网上的患者接口。
 */

@ConfigurationProperties("cardio-ops.device-platform")
public record DevicePlatformScheduledMissApiProperties(
        String baseUrl,
        String serviceToken,
        Duration timeout
) {
    public DevicePlatformScheduledMissApiProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("cardio-ops.device-platform.base-url must not be blank");
        }
        if (serviceToken == null || serviceToken.isBlank()) {
            throw new IllegalArgumentException("cardio-ops.device-platform.service-token must not be blank when agent is enabled");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("cardio-ops.device-platform.timeout must be positive");
        }
    }
}
