package io.cardioops.infrastructure.integration.scheduledmiss;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 连 DevicePlatform MCP 的服务身份。令牌只从环境或 Secret 来。actorRole 必须是 SYSTEM_WORKER，别的角色配上来就启动失败。
 * 模型的工具入参里没有角色字段，报不了另一个身份。
 *
 *
 * 参数是模型看得到的。角色如果从参数来，模型就能自称别人。角色钉在这份配置上。
 */

@ConfigurationProperties("cardio-ops.device-platform.mcp")
public record DevicePlatformMcpClientProperties(
        String serverName,
        String serviceToken,
        String actorRole
) {
    public DevicePlatformMcpClientProperties {
        if (serverName == null || serverName.isBlank()) {
            throw new IllegalArgumentException("cardio-ops.device-platform.mcp.server-name must not be blank");
        }
        if (serviceToken == null || serviceToken.isBlank()) {
            throw new IllegalArgumentException("cardio-ops.device-platform.mcp.service-token must not be blank");
        }
        if (!"SYSTEM_WORKER".equals(actorRole)) {
            throw new IllegalArgumentException(
                    "cardio-ops.device-platform.mcp.actor-role must be SYSTEM_WORKER for the worker runtime");
        }
    }
}
