package io.cardioops.agent.scheduledmiss;

import io.cardioops.application.incident.IncidentPage;
import io.cardioops.application.incident.IncidentQuery;
import io.cardioops.application.incident.IncidentQueryUseCase;
import io.cardioops.application.agentrun.AgentRunStore;
import io.cardioops.application.scheduledmiss.ScheduledMissAgentPolicySnapshot;
import io.cardioops.application.scheduledmiss.ScheduledMissInvestigationStore;
import io.cardioops.application.scheduledmiss.ScheduledMissEvidenceScope;
import io.cardioops.application.scheduledmiss.ScheduledMissEvidenceSource;
import io.cardioops.application.agentrun.AgentToolCallAudit;
import io.cardioops.application.knowledge.UnavailableVersionedKnowledgeSearch;
import io.cardioops.domain.agent.AgentApplicationRegistry;
import io.cardioops.domain.agent.AgentRun;
import io.cardioops.domain.agent.AgentRunStatus;
import io.cardioops.domain.device.DeviceDescriptor;
import io.cardioops.domain.device.DeviceFamily;
import io.cardioops.domain.scheduledmiss.GatewayStatusSnapshot;
import io.cardioops.domain.scheduledmiss.IcmTriggerPolicyRegistry;
import io.cardioops.domain.incident.Incident;
import io.cardioops.domain.incident.IncidentEvidence;
import io.cardioops.domain.incident.IncidentSeverity;
import io.cardioops.domain.incident.IncidentStatus;
import io.cardioops.domain.incident.IncidentType;
import io.cardioops.domain.scheduledmiss.ScheduledMissInvestigationResult;
import io.cardioops.domain.device.ProfileResolutionStatus;
import io.cardioops.domain.scheduledmiss.ScheduledMissHandlingRecommendation;
import io.cardioops.domain.scheduledmiss.ScheduleChangeObservation;
import io.cardioops.domain.scheduledmiss.ScheduledMissContext;
import org.junit.jupiter.api.Test;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证未经确认的 ICD 语义不会进入模型，而是由 Graph 确定性地安全降级。 */
class ScheduledMissInvestigationGraphTest {

    @Test
    void unsupportedIcdProfileFallsBackWithoutCallingModelOrTools() throws Exception {
        Instant now = Instant.parse("2026-09-06T03:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        var run = new AgentRun("run-icd", "incident-icd", "event-1", "thread-icd",
                AgentRunStatus.RUNNING, "icd-v1", "trace-1", 1,
                "worker-1", "lease-1", now.plusSeconds(60), now, now);
        var device = new DeviceDescriptor(DeviceFamily.ICD, "ICD-A", "1.0", 100);
        var incident = new Incident(
                "incident-icd", "key-icd", "device-icd", device,
                IncidentType.SCHEDULED_GATEWAY_TRANSMISSION_MISSED,
                IncidentSeverity.UNASSESSED, IncidentStatus.DETECTED,
                "MONITORING:device-icd:1", now, now,
                ProfileResolutionStatus.SUPPORTED, "icd-v1", 0, now, now, null);
        var store = new SingleRunStore(run);
        var unavailableKnowledge = new UnavailableVersionedKnowledgeSearch();
        var graph = new ScheduledMissInvestigationGraph(
                store, new NoOpInvestigationStore(), new SingleIncidentQueries(incident),
                List::of, new AgentApplicationRegistry(List.of()),
                new IcmTriggerPolicyRegistry(List.of()),
                new NeverCalledEvidenceSource(), unavailableKnowledge,
                new SpringAiScheduledMissAgent(prompt -> {
                    throw new AssertionError("unsupported ICD must not call the model");
                }, "never-called", clock, unavailableKnowledge, new SimpleMeterRegistry()),
                new ScheduledMissResultValidator(Duration.ofMinutes(3), clock),
                new ObjectMapper(), new MemorySaver(), clock);

        ScheduledMissInvestigationResult result = graph.execute("run-icd");

        assertThat(result.recovered()).isFalse();
        assertThat(result.recommendation()).isEqualTo(ScheduledMissHandlingRecommendation.HUMAN_REVIEW);
        assertThat(result.missingEvidence()).containsExactly(
                "当前应传未传排查能力尚未核对该设备族或 Profile 的触发语义");
    }

    private static final class SingleRunStore implements AgentRunStore {
        private final AgentRun run;
        private SingleRunStore(AgentRun run) { this.run = run; }
        @Override public Optional<AgentRun> claimNextRunnableRun(io.cardioops.application.agentrun.AgentRunClaimPolicy policy) { return Optional.empty(); }
        @Override public Optional<AgentRun> findRun(String runId) { return Optional.of(run); }
        @Override public boolean renewLease(String runId, String leaseToken, Duration leaseDuration) { return true; }
        @Override public void markCompleted(String runId, String leaseToken) { }
        @Override public void markFailed(String runId, String leaseToken, String errorCode) { }
    }

    private static final class NoOpInvestigationStore implements ScheduledMissInvestigationStore {
        @Override public void savePolicySnapshotIfAbsent(ScheduledMissAgentPolicySnapshot snapshot) { }
        @Override public Optional<ScheduledMissAgentPolicySnapshot> findPolicySnapshot(String runId) {
            return Optional.empty();
        }
        @Override public void appendToolCall(AgentToolCallAudit audit) { throw new AssertionError("no tool expected"); }
        @Override public void saveResult(ScheduledMissInvestigationResult result) { }
        @Override public Optional<ScheduledMissInvestigationResult> findResult(String runId) { return Optional.empty(); }
    }

    private record SingleIncidentQueries(Incident incident) implements IncidentQueryUseCase {
        @Override public IncidentPage list(IncidentQuery query) { throw new UnsupportedOperationException(); }
        @Override public Optional<Incident> findById(String incidentId) { return Optional.of(incident); }
        @Override public List<IncidentEvidence> listEvidence(String incidentId) { return List.of(); }
        @Override public List<AgentRun> listRuns(String incidentId) { return List.of(); }
    }

    private static final class NeverCalledEvidenceSource implements ScheduledMissEvidenceSource {
        @Override public ScheduledMissContext getScheduledMissContext(ScheduledMissEvidenceScope scope, String traceId) {
            throw new AssertionError("no tool expected");
        }
        @Override public List<io.cardioops.application.scheduledmiss.DevicePlatformTransmissionObservation> queryTransmissionsInWindow(
                ScheduledMissEvidenceScope scope, String traceId) {
            throw new AssertionError("no tool expected");
        }
        @Override public GatewayStatusSnapshot getCurrentGatewaySnapshot(ScheduledMissEvidenceScope scope, String traceId) {
            throw new AssertionError("no tool expected");
        }
        @Override public List<ScheduleChangeObservation> queryScheduleChangeHistory(
                ScheduledMissEvidenceScope scope, String traceId) {
            throw new AssertionError("no tool expected");
        }
    }
}
