package io.cardioops.worker.scheduledmiss;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 应传未传场景独有的宽限期。Agent 开关和模型名属于公共运行时配置，不放在这里。
 */

@ConfigurationProperties("cardio-ops.agent")
public record ScheduledMissAgentProperties(
        Duration scheduledTransmissionGrace
) {
    public ScheduledMissAgentProperties {
        if (scheduledTransmissionGrace == null || scheduledTransmissionGrace.isNegative()) {
            throw new IllegalArgumentException("scheduled-transmission-grace must not be negative");
        }
    }
}
