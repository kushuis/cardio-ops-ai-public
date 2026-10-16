package io.cardioops.infrastructure.integration;

import io.cardioops.application.integrationevent.IntegrationEventCommand;
import io.cardioops.application.integrationevent.IntegrationIntakeResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Clock;
import java.time.Duration;

/**
 * 指标标签只用事件类型、处理状态、档案状态这种有限集合。deviceRef、eventId、traceId 进日志，不进标签，不然时序库会被撑开。
 */
public final class IntegrationEventMetrics {

    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public IntegrationEventMetrics(MeterRegistry meterRegistry, Clock clock) {
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    public void recordIntake(IntegrationEventCommand command, IntegrationIntakeResult result) {
        Counter.builder("cardio_ops.integration.intake")
                .description("Integration intake results")
                .tag("event_type", command.eventType().name())
                .tag("result", result.status().name())
                .tag("profile_status", result.profileStatus() == null
                        ? "NOT_RESOLVED" : result.profileStatus().name())
                .register(meterRegistry)
                .increment();

        Duration latency = Duration.between(command.occurredAt(), clock.instant());
        if (!latency.isNegative()) {
            Timer.builder("cardio_ops.integration.event_latency")
                    .description("Delay from business occurrence to successful intake")
                    .tag("event_type", command.eventType().name())
                    .register(meterRegistry)
                    .record(latency);
        }
    }

    public void recordDeadLetter(String failureType) {
        Counter.builder("cardio_ops.integration.dead_letter")
                .description("Messages received from the integration DLT")
                .tag("failure_type", normalizeFailureType(failureType))
                .register(meterRegistry)
                .increment();
    }

    private String normalizeFailureType(String failureType) {
        if (failureType == null || failureType.isBlank()) {
            return "UNKNOWN";
        }
        int lastDot = failureType.lastIndexOf('.');
        return lastDot < 0 ? failureType : failureType.substring(lastDot + 1);
    }
}
