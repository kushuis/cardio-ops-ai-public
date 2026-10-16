package io.cardioops.agent.scheduledmiss;

import io.cardioops.domain.scheduledmiss.GatewayStatusSnapshot;
import io.cardioops.domain.scheduledmiss.ScheduleChangeObservation;
import io.cardioops.domain.scheduledmiss.ScheduledMissContext;
import io.cardioops.domain.scheduledmiss.TransmissionObservation;
import io.cardioops.domain.knowledge.KnowledgeSnippet;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 一次调查的内存账本。模型看到的是工具 JSON，校验读的是这里的对象。两边来自同一次工具执行，校验不用再去解析模型的原文。
 * 知识片段也记在这里，但它是说明，不是设备证据。
 *
 *
 * 模型可以把证据复述错。账本是工具返回时留下的强类型副本，规则只信这个。
 */
final class ScheduledMissEvidenceLedger {

    private ScheduledMissContext context;
    private final List<TransmissionObservation> transmissions = new ArrayList<>();
    private boolean transmissionsQueried;
    private GatewayStatusSnapshot currentGateway;
    private boolean currentGatewayQueried;
    private final List<ScheduleChangeObservation> scheduleChanges = new ArrayList<>();
    private boolean scheduleChangesQueried;
    private final List<KnowledgeSnippet> retrievedKnowledge = new ArrayList<>();
    private final Set<String> evidenceRefs = new LinkedHashSet<>();

    synchronized void recordContext(ScheduledMissContext value) {
        context = value;
        evidenceRefs.add(value.evidenceRef());
    }

    synchronized void recordTransmissions(List<TransmissionObservation> values) {
        transmissionsQueried = true;
        transmissions.clear();
        transmissions.addAll(values);
        values.forEach(value -> evidenceRefs.add(value.evidenceRef()));
    }

    synchronized void recordCurrentGateway(GatewayStatusSnapshot value) {
        currentGatewayQueried = true;
        currentGateway = value;
        evidenceRefs.add(value.evidenceRef());
    }

    synchronized void recordScheduleChanges(List<ScheduleChangeObservation> values) {
        scheduleChangesQueried = true;
        scheduleChanges.clear();
        scheduleChanges.addAll(values);
        values.forEach(value -> evidenceRefs.add(value.evidenceRef()));
    }

    synchronized void recordKnowledge(List<KnowledgeSnippet> values) {
        retrievedKnowledge.clear();
        retrievedKnowledge.addAll(values);
    }

    synchronized Optional<ScheduledMissContext> context() {
        return Optional.ofNullable(context);
    }

    synchronized List<TransmissionObservation> transmissions() {
        return List.copyOf(transmissions);
    }

    synchronized boolean transmissionsQueried() {
        return transmissionsQueried;
    }

    synchronized Optional<GatewayStatusSnapshot> currentGateway() {
        return Optional.ofNullable(currentGateway);
    }

    synchronized List<ScheduleChangeObservation> scheduleChanges() {
        return List.copyOf(scheduleChanges);
    }

    synchronized List<KnowledgeSnippet> retrievedKnowledge() {
        return List.copyOf(retrievedKnowledge);
    }

    synchronized Set<String> evidenceRefs() {
        return Set.copyOf(evidenceRefs);
    }

    synchronized CollectedScheduledMissEvidence snapshot() {
        return new CollectedScheduledMissEvidence(
                context, List.copyOf(transmissions), transmissionsQueried,
                currentGateway, currentGatewayQueried, List.copyOf(scheduleChanges),
                scheduleChangesQueried, List.copyOf(retrievedKnowledge));
    }
}
