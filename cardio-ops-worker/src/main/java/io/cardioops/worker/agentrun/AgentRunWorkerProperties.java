package io.cardioops.worker.agentrun;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 轮询间隔、租约时长、心跳间隔、最多尝试几次。没有医疗规则。心跳必须短于租约，不然续租还没发生租约就过期了。
 */

@ConfigurationProperties("cardio-ops.agent.worker")
public record AgentRunWorkerProperties(
        String instanceId,
        Duration pollDelay,
        Duration leaseDuration,
        Duration heartbeatInterval,
        int maxAttempts
) {
    public AgentRunWorkerProperties {
        requirePositive(pollDelay, "poll-delay");
        requirePositive(leaseDuration, "lease-duration");
        requirePositive(heartbeatInterval, "heartbeat-interval");
        if (heartbeatInterval.compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException("heartbeat-interval must be shorter than lease-duration");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("max-attempts must be at least one");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
