package io.cardioops.agent.lowbattery;

import io.cardioops.domain.agent.AgentRun;
import io.cardioops.domain.incident.Incident;
import io.cardioops.domain.lowbattery.LowBatteryImpactAssessment;
import io.cardioops.domain.lowbattery.LowBatteryInvestigationResult;
import io.cardioops.domain.scheduledmiss.GatewayConnectivity;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;

/** 用受治理工具的证据重算结论，模型草稿只能提供解释。 */
public final class LowBatteryResultValidator {
    private final Clock clock;

    public LowBatteryResultValidator(Clock clock) { this.clock = clock; }

    public LowBatteryInvestigationResult validate(AgentRun run, Incident incident, LowBatteryAgentOutput output) {
        var evidence = output.evidence();
        var transmissions = evidence.transmissions().stream()
                .sorted(java.util.Comparator.comparing(
                        io.cardioops.domain.lowbattery.LowBatteryTransmissionObservation::completedAt)
                        .reversed()).toList();
        LowBatteryImpactAssessment assessment;
        if (transmissions.isEmpty()) {
            assessment = LowBatteryImpactAssessment.INSUFFICIENT_EVIDENCE;
        } else if (transmissions.getFirst().complete()) {
            assessment = LowBatteryImpactAssessment.NO_OBSERVED_IMPACT;
        } else {
            assessment = LowBatteryImpactAssessment.POSSIBLE_IMPACT;
        }

        var allowedRefs = new LinkedHashSet<String>();
        allowedRefs.add(evidence.context().evidenceRef());
        allowedRefs.add(evidence.gateway().evidenceRef());
        transmissions.forEach(item -> allowedRefs.add(item.evidenceRef()));
        List<String> cited = safe(output.draft().evidenceRefs()).stream().filter(allowedRefs::contains).toList();
        boolean gatewayAvailable = evidence.gateway().connectivity() == GatewayConnectivity.ONLINE;
        return new LowBatteryInvestigationResult(run.runId(), incident.incidentId(), assessment,
                gatewayAvailable, safe(output.draft().observations()), safe(output.draft().hypotheses()),
                safe(output.draft().contradictingEvidence()), safe(output.draft().missingEvidence()),
                cited, output.draft().summary() == null ? "低电量影响调查已完成，等待人工复核。" : output.draft().summary(),
                clock.instant());
    }

    private static List<String> safe(List<String> value) { return value == null ? List.of() : List.copyOf(value); }
}
