package io.cardioops.agent.scheduledmiss;

import io.cardioops.domain.scheduledmiss.GatewayStatusSnapshot;
import io.cardioops.domain.scheduledmiss.ScheduleChangeObservation;
import io.cardioops.domain.scheduledmiss.ScheduledMissContext;
import io.cardioops.domain.scheduledmiss.TransmissionObservation;
import io.cardioops.domain.knowledge.KnowledgeSnippet;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 交给校验的强类型证据。上下文可能还空着，传输窗口要单独记有没有真的查过。空列表和“没查”不是一回事，没查要被校验拒绝。
 */
public record CollectedScheduledMissEvidence(
        ScheduledMissContext context,
        List<TransmissionObservation> transmissions,
        boolean transmissionsQueried,
        GatewayStatusSnapshot currentGateway,
        boolean currentGatewayQueried,
        List<ScheduleChangeObservation> scheduleChanges,
        boolean scheduleChangesQueried,
        List<KnowledgeSnippet> retrievedKnowledge
) {
    public CollectedScheduledMissEvidence {
        transmissions = List.copyOf(transmissions);
        scheduleChanges = List.copyOf(scheduleChanges);
        retrievedKnowledge = List.copyOf(retrievedKnowledge == null ? List.of() : retrievedKnowledge);
    }

    /** 汇总模型允许引用的真实 Evidence ID。 */
    public Set<String> evidenceRefs() {
        var refs = new LinkedHashSet<String>();
        if (context != null) {
            refs.add(context.evidenceRef());
        }
        transmissions.forEach(value -> refs.add(value.evidenceRef()));
        if (currentGateway != null) {
            refs.add(currentGateway.evidenceRef());
        }
        scheduleChanges.forEach(value -> refs.add(value.evidenceRef()));
        return Set.copyOf(refs);
    }
}
