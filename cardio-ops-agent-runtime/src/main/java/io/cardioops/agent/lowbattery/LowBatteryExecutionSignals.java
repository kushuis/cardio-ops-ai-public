package io.cardioops.agent.lowbattery;

import io.cardioops.application.agentrun.AgentRunLeaseLostException;
import io.cardioops.domain.agent.AgentRun;
import io.cardioops.domain.lowbattery.LowBatteryImpactAssessment;
import io.cardioops.domain.lowbattery.LowBatteryInvestigationResult;

import java.time.Instant;
import java.util.List;

/** 区分租约丢失、证据没取齐，以及其他技术失败。证据没取齐按人工口径收束，不把 Run 标失败。 */
final class LowBatteryExecutionSignals {
    private LowBatteryExecutionSignals() {
    }

    static boolean leaseLost(Throwable error) {
        return hasCause(error, AgentRunLeaseLostException.class);
    }

    static boolean insufficientEvidence(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && (message.contains("LOW_BATTERY_REQUIRED_EVIDENCE_MISSING")
                    || message.contains("CONTEXT_MUST_BE_LOADED_FIRST"))) {
                return true;
            }
        }
        return false;
    }

    static LowBatteryInvestigationResult insufficientResult(AgentRun run, Instant completedAt) {
        LowBatteryImpactAssessment assessment = LowBatteryImpactAssessment.INSUFFICIENT_EVIDENCE;
        return new LowBatteryInvestigationResult(run.runId(), run.incidentId(), assessment, false,
                List.of(), List.of(), List.of(), List.of("所需只读证据没有取齐"), List.of(),
                assessment.listText(), completedAt);
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }
}
