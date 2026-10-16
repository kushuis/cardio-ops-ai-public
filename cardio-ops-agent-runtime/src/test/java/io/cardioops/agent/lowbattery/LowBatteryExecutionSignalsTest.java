package io.cardioops.agent.lowbattery;

import io.cardioops.application.agentrun.AgentRunLeaseLostException;
import io.cardioops.domain.agent.AgentRun;
import io.cardioops.domain.agent.AgentRunStatus;
import io.cardioops.domain.lowbattery.LowBatteryImpactAssessment;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LowBatteryExecutionSignalsTest {
    @Test
    void missingEvidenceFinishesAsInsufficientInsteadOfATechnicalFailure() {
        var wrapped = new IllegalStateException("tool loop failed",
                new IllegalStateException("LOW_BATTERY_REQUIRED_EVIDENCE_MISSING"));
        var unordered = new RuntimeException("CONTEXT_MUST_BE_LOADED_FIRST");

        assertThat(LowBatteryExecutionSignals.insufficientEvidence(wrapped)).isTrue();
        assertThat(LowBatteryExecutionSignals.insufficientEvidence(unordered)).isTrue();
        assertThat(LowBatteryExecutionSignals.leaseLost(wrapped)).isFalse();

        var result = LowBatteryExecutionSignals.insufficientResult(run(), Instant.parse("2026-10-15T00:00:00Z"));
        assertThat(result.impactAssessment()).isEqualTo(LowBatteryImpactAssessment.INSUFFICIENT_EVIDENCE);
        assertThat(result.summary()).isEqualTo(LowBatteryImpactAssessment.INSUFFICIENT_EVIDENCE.listText());
    }

    @Test
    void leaseLossIsNotTurnedIntoInsufficientEvidence() {
        var lost = new IllegalStateException("stopped", new AgentRunLeaseLostException("lease lost"));
        assertThat(LowBatteryExecutionSignals.leaseLost(lost)).isTrue();
        assertThat(LowBatteryExecutionSignals.insufficientEvidence(lost)).isFalse();
    }

    private static AgentRun run() {
        Instant now = Instant.parse("2026-10-15T00:00:00Z");
        return new AgentRun("run-1", "incident-1", "event-1", "thread-1", AgentRunStatus.RUNNING,
                "demo-icm-v1", "trace-1", 1, "worker", "token", now.plusSeconds(60), now, now);
    }
}
