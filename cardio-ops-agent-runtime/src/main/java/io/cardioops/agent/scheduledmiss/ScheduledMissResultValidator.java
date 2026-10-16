package io.cardioops.agent.scheduledmiss;

import io.cardioops.domain.agent.AgentRun;
import io.cardioops.domain.scheduledmiss.EvidenceUploadChannel;
import io.cardioops.domain.scheduledmiss.GatewayConnectivity;
import io.cardioops.domain.scheduledmiss.IcmTransmissionTrigger;
import io.cardioops.domain.incident.Incident;
import io.cardioops.domain.scheduledmiss.ScheduledMissFact;
import io.cardioops.domain.scheduledmiss.ScheduledMissHypothesis;
import io.cardioops.domain.scheduledmiss.ScheduledMissInvestigationResult;
import io.cardioops.domain.scheduledmiss.ScheduledMissHandlingRecommendation;
import io.cardioops.domain.scheduledmiss.ScheduleChangeObservation;
import io.cardioops.domain.scheduledmiss.ScheduledMissContext;
import io.cardioops.domain.scheduledmiss.TransmissionObservation;
import io.cardioops.domain.knowledge.KnowledgeCitation;
import io.cardioops.domain.knowledge.KnowledgeSnippet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 把模型草稿和强类型证据收成最终结果。recovered 在这里重算，不读模型的意见。
 * 草稿里本来也没有这一位。模型就算在摘要里写“已经恢复了”，这个类也不看那句话。
 *
 * <p>恢复规则取决于首次漏传时的上下文：当时网关离线/未绑定，后续任意一次网关传输就能证明该阻断已解除；
 * 当时网关在线，则必须看到下一次真正的定时网关传输，磁铁、事件和告警都不能替代“应传实传”。
 * 改期仍是另一条硬规则。模型只负责解释，不能改这组判断。</p>
 *
 * <p>恢复了建议无需联系，否则建议人工复核。建议写进结果，图拿到结果就走到 END，不会停下来等人。
 * 人工批准不是图上的中断。</p>
 *
 * <p>
 * 模型拿不到这个字段。提示会被带偏，输出也会被污染。只有证据按硬规则重算，结果才站得住。</p>
 */
public final class ScheduledMissResultValidator {

    private static final Logger log = LoggerFactory.getLogger(ScheduledMissResultValidator.class);
    private final Duration gracePeriod;
    private final Clock clock;

    public ScheduledMissResultValidator(Duration gracePeriod, Clock clock) {
        if (gracePeriod.isNegative()) {
            throw new IllegalArgumentException("gracePeriod must not be negative");
        }
        this.gracePeriod = gracePeriod;
        this.clock = clock;
    }

    /**
     * 没有上下文，或者传输窗口根本没查过，就不算调查完成，直接失败。
     * 证据上的 incident 和设备必须就是这单，防止把另一台设备的事实算进 recovered。
     *
     * <p>
     * 宽限期只记 withinGrace。窗口一直开到下一次应传。这段里出现自动唤醒，或者旧时刻前改过广播时间，recovered 就是 true。</p>
     */
    ScheduledMissInvestigationResult validate(
            AgentRun run,
            Incident incident,
            ScheduledMissAgentOutput output
    ) {
        CollectedScheduledMissEvidence evidence = output.evidence();
        ScheduledMissContext context = evidence.context();
        if (context == null) {
            throw new IllegalStateException("REQUIRED_CONTEXT_NOT_COLLECTED");
        }
        if (!evidence.transmissionsQueried()) {
            throw new IllegalStateException("REQUIRED_TRANSMISSION_WINDOW_NOT_QUERIED");
        }
        if (!evidence.currentGatewayQueried()) {
            throw new IllegalStateException("REQUIRED_CURRENT_GATEWAY_NOT_QUERIED");
        }
        if (!evidence.scheduleChangesQueried()) {
            throw new IllegalStateException("REQUIRED_SCHEDULE_CHANGES_NOT_QUERIED");
        }
        if (!context.incidentId().equals(incident.incidentId())
                || !context.deviceRef().equals(incident.deviceRef())) {
            throw new IllegalStateException("EVIDENCE_SCOPE_MISMATCH");
        }
        Instant checkedAt = clock.instant();
        List<TransmissionObservation> observationsAfterMiss = evidence.transmissions().stream()
                .filter(value -> !value.completedAt().isBefore(context.expectedTransmissionAt()))
                .filter(value -> !value.completedAt().isAfter(checkedAt))
                .toList();
        List<TransmissionObservation> automaticWakeups = observationsAfterMiss.stream()
                .filter(TransmissionObservation::provesAutomaticWakeup)
                .toList();
        boolean automaticWakeupObserved = !automaticWakeups.isEmpty();
        boolean gatewayUnavailableAtMiss = context.gatewayAtExpected() != GatewayConnectivity.ONLINE;
        List<TransmissionObservation> recoveryTransmissions = observationsAfterMiss.stream()
                .filter(value -> value.channel() == EvidenceUploadChannel.GATEWAY)
                .filter(value -> gatewayUnavailableAtMiss
                        || (value.trigger() == IcmTransmissionTrigger.SCHEDULED
                        && (!value.completedAt().isAfter(
                                context.expectedTransmissionAt().plus(gracePeriod))
                        || !value.completedAt().isBefore(context.nextExpectedTransmissionAt()))))
                .toList();
        boolean recoveryTransmissionObserved = !recoveryTransmissions.isEmpty();
        boolean withinGrace = recoveryTransmissions.stream().anyMatch(value ->
                !value.completedAt().isAfter(context.expectedTransmissionAt().plus(gracePeriod))
                        || (!value.completedAt().isBefore(context.nextExpectedTransmissionAt())
                        && !value.completedAt().isAfter(
                                context.nextExpectedTransmissionAt().plus(gracePeriod))));
        boolean scheduleChanged = evidence.scheduleChanges().stream()
                .anyMatch(change -> change.invalidatesOldExpectedTime(context.expectedTransmissionAt()));
        boolean recovered = recoveryTransmissionObserved || scheduleChanged;
        ScheduledMissHandlingRecommendation recommendation = recovered
                ? ScheduledMissHandlingRecommendation.NO_CONTACT_NEEDED
                : ScheduledMissHandlingRecommendation.HUMAN_REVIEW;

        Set<String> knownEvidenceRefs = evidence.evidenceRefs();
        List<ScheduledMissHypothesis> hypotheses = validateHypotheses(
                output.draft().hypotheses(), knownEvidenceRefs);
        List<KnowledgeCitation> knowledgeCitations = validateKnowledgeCitations(
                output.draft().knowledgeRefs(), evidence.retrievedKnowledge());
        var missing = new LinkedHashSet<>(output.draft().missingEvidence());
        if (!recovered) {
            // 缺少直接遥测证明应传时刻设备已唤醒；未恢复时显式记录证据缺口，防止摘要消除不确定性。
            missing.add("应传时刻设备是否真正完成自动唤醒（当前系统没有直接遥测）");
        }

        List<ScheduledMissFact> facts = buildFacts(
                context, evidence, recoveryTransmissions, gatewayUnavailableAtMiss, scheduleChanged);
        ScheduledMissInvestigationResult result = new ScheduledMissInvestigationResult(
                run.runId(), incident.incidentId(), automaticWakeupObserved,
                recoveryTransmissionObserved, recovered, withinGrace,
                scheduleChanged, recommendation, knownEvidenceRefs.stream().sorted().toList(), facts,
                hypotheses, knowledgeCitations, List.copyOf(missing), output.draft().summary(), clock.instant());
        log.info("investigation result validated runId={} incidentId={} automaticWakeupObserved={} "
                        + "recoveryTransmissionObserved={} gatewayUnavailableAtMiss={} withinGrace={} "
                        + "scheduleChangedBeforeExpected={} recovered={} recommendation={}",
                run.runId(), incident.incidentId(), automaticWakeupObserved, recoveryTransmissionObserved,
                gatewayUnavailableAtMiss, withinGrace,
                scheduleChanged, recovered, recommendation);
        return result;
    }

    /**
     * 知识引用必须是这次本地检索真正返回的 knowledgeRef。模型编一个文档号，或者拿历史版本来充，就失败。
     * 同一个 ref 有多条时留下相似度更高的那条。知识进的是引用，不是 recovered 的证据。
     *
     * <p>
     * 忽略会让草稿看起来有出处。失败才能说明这份解释用了没拿到的材料。</p>
     */
    private List<KnowledgeCitation> validateKnowledgeCitations(
            List<String> citedKnowledgeRefs,
            List<KnowledgeSnippet> retrievedKnowledge
    ) {
        Map<String, KnowledgeSnippet> byRef = retrievedKnowledge.stream().collect(Collectors.toMap(
                KnowledgeSnippet::knowledgeRef, Function.identity(),
                (left, right) -> left.similarityScore() >= right.similarityScore() ? left : right));
        var citations = new ArrayList<KnowledgeCitation>();
        var unique = new LinkedHashSet<>(citedKnowledgeRefs);
        for (String knowledgeRef : unique) {
            KnowledgeSnippet snippet = byRef.get(knowledgeRef);
            if (snippet == null) {
                throw new IllegalStateException("MODEL_CITED_UNKNOWN_KNOWLEDGE");
            }
            citations.add(KnowledgeCitation.from(snippet));
        }
        return List.copyOf(citations);
    }

    /**
     * 假设只能引用这次工具返回过的 evidenceRef。支持项和反证都算。
     * 有一条对不上，整份草稿失败，不把那条假设悄悄删掉。
     *
     * <p>
     * 丢掉以后结果是绿的，审查的人不知道模型编过证据。整份失败，问题才留在这次执行上。</p>
     */
    private List<ScheduledMissHypothesis> validateHypotheses(
            List<ScheduledMissHypothesis> hypotheses,
            Set<String> knownEvidenceRefs
    ) {
        for (var hypothesis : hypotheses) {
            var cited = new LinkedHashSet<String>();
            cited.addAll(hypothesis.supportingEvidenceRefs());
            cited.addAll(hypothesis.contradictingEvidenceRefs());
            if (!knownEvidenceRefs.containsAll(cited)) {
                throw new IllegalStateException("MODEL_CITED_UNKNOWN_EVIDENCE");
            }
        }
        return List.copyOf(hypotheses);
    }

    private List<ScheduledMissFact> buildFacts(
            ScheduledMissContext context,
            CollectedScheduledMissEvidence evidence,
            List<TransmissionObservation> recoveryTransmissions,
            boolean gatewayUnavailableAtMiss,
            boolean scheduleChanged
    ) {
        var facts = new ArrayList<ScheduledMissFact>();
        facts.add(new ScheduledMissFact(context.evidenceRef(),
                "应传时刻网关状态为 " + context.gatewayAtExpected().name()));
        for (var transmission : recoveryTransmissions) {
            boolean nextCycle = !transmission.completedAt().isBefore(context.nextExpectedTransmissionAt());
            facts.add(new ScheduledMissFact(transmission.evidenceRef(),
                    gatewayUnavailableAtMiss
                            ? "首次漏传时网关不可用；后续发现网关传输，阻断条件已经解除"
                            : nextCycle
                            ? "首次漏传时网关在线；后续发现下一周期的定时应传实传"
                            : "首次漏传时网关在线；宽限期内发现定时应传实传"));
        }
        evidence.transmissions().stream()
                .filter(value -> value.channel() == EvidenceUploadChannel.MINI_PROGRAM
                        || !value.trigger().provesAutomaticWakeup())
                .forEach(value -> facts.add(new ScheduledMissFact(value.evidenceRef(),
                        "发现手动传输，能够证明上传链路可用，但不能证明自动唤醒正常")));
        if (scheduleChanged) {
            evidence.scheduleChanges().stream()
                    .filter(change -> change.invalidatesOldExpectedTime(context.expectedTransmissionAt()))
                    .map(ScheduleChangeObservation::evidenceRef)
                    .findFirst()
                    .ifPresent(ref -> facts.add(new ScheduledMissFact(ref,
                            "旧应传时间前已成功写入不同的新广播时间")));
        }
        return List.copyOf(facts);
    }
}
