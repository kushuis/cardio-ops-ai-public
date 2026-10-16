package io.cardioops.application.agentrun;

import java.time.Duration;

/**
 * 领取时的技术参数：哪个 Worker、租约多久、最多试几次。没有医疗规则。
 * 租约必须靠心跳续上，不然还在跑的任务会被别人接走。
 */
public record AgentRunClaimPolicy(
        String workerId,
        Duration leaseDuration,
        int maxAttempts
) {
    public AgentRunClaimPolicy {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least one");
        }
    }
}
