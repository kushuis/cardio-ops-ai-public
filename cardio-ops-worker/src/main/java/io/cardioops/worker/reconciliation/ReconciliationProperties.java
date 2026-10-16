package io.cardioops.worker.reconciliation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 巡检间隔和超时。服务令牌空着只关掉手动 HTTP，定时扫描还在。扫描仍然只计数。
 */

@ConfigurationProperties("cardio-ops.reconciliation")
public record ReconciliationProperties(
        Duration runningTimeout,
        Duration approvalTimeout,
        String serviceToken
) {
    public ReconciliationProperties {
        runningTimeout = runningTimeout == null ? Duration.ofMinutes(10) : runningTimeout;
        approvalTimeout = approvalTimeout == null ? Duration.ofHours(24) : approvalTimeout;
        serviceToken = serviceToken == null ? "" : serviceToken.trim();
    }
}
