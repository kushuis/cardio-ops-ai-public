package io.cardioops.agent.agentrun;

import io.cardioops.application.agentrun.AgentRunClaimPolicy;
import io.cardioops.application.agentrun.AgentRunExecutionObserver;
import io.cardioops.application.agentrun.AgentRunHandler;
import io.cardioops.application.agentrun.AgentRunLeaseHeartbeat;
import io.cardioops.application.agentrun.AgentRunLeaseGuard;
import io.cardioops.application.agentrun.AgentRunLeaseLostException;
import io.cardioops.application.agentrun.AgentRunStore;
import io.cardioops.application.incident.IncidentPage;
import io.cardioops.application.incident.IncidentQuery;
import io.cardioops.application.incident.IncidentQueryUseCase;
import io.cardioops.domain.agent.AgentRun;
import io.cardioops.domain.agent.AgentRunStatus;
import io.cardioops.domain.device.DeviceDescriptor;
import io.cardioops.domain.device.DeviceFamily;
import io.cardioops.domain.device.ProfileResolutionStatus;
import io.cardioops.domain.incident.Incident;
import io.cardioops.domain.incident.IncidentEvidence;
import io.cardioops.domain.incident.IncidentSeverity;
import io.cardioops.domain.incident.IncidentStatus;
import io.cardioops.domain.incident.IncidentType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证公共 Worker 始终使用本次 leaseToken，并且租约冲突不会覆盖新 Worker。 */
class AgentRunWorkerTest {

    private static final Instant NOW = Instant.parse("2026-09-06T03:00:00Z");

    @Test
    void marksFailureWithCurrentLeaseAndClosesHeartbeat() {
        var store = new RecordingStore(false);
        var heartbeat = new RecordingHeartbeat();
        var observer = new RecordingObserver();
        var worker = worker(store, heartbeat, observer);

        assertThat(worker.executeNext()).isTrue();

        assertThat(store.failedLeaseToken).isEqualTo("lease-1");
        assertThat(store.failureCode).isEqualTo("IllegalStateException");
        assertThat(heartbeat.closed).isTrue();
        assertThat(observer.failed).isTrue();
    }

    @Test
    void doesNotOverwriteNewOwnerWhenOldLeaseHasAlreadyBeenRejected() {
        var store = new RecordingStore(true);
        var worker = worker(store, new RecordingHeartbeat(), new RecordingObserver());

        assertThat(worker.executeNext()).isTrue();
        assertThat(store.failureAttempts).isEqualTo(1);
    }

    @Test
    void stopsWithoutMarkingFailedWhenBusinessDetectsLostLease() {
        var store = new RecordingStore(false);
        var heartbeat = new RecordingHeartbeat();
        var observer = new RecordingObserver();
        var dispatcher = new AgentRunDispatcher(new SingleIncidentQueries(), List.of(new LeaseLostHandler()));
        var worker = new AgentRunWorker(
                store, dispatcher,
                new AgentRunClaimPolicy("worker-1", Duration.ofMinutes(1), 3),
                heartbeat, observer, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(worker.executeNext()).isTrue();
        assertThat(store.failureAttempts).isZero();
        assertThat(observer.leaseLost).isTrue();
        assertThat(heartbeat.closed).isTrue();
    }

    private static AgentRunWorker worker(
            RecordingStore store,
            RecordingHeartbeat heartbeat,
            RecordingObserver observer
    ) {
        var dispatcher = new AgentRunDispatcher(new SingleIncidentQueries(), List.of(new FailingHandler()));
        return new AgentRunWorker(
                store, dispatcher,
                new AgentRunClaimPolicy("worker-1", Duration.ofMinutes(1), 3),
                heartbeat, observer, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AgentRun run() {
        return new AgentRun(
                "run-1", "incident-1", "event-1", "thread-1", AgentRunStatus.RUNNING,
                "demo-icm-v1", "trace-1", 1, "worker-1", "lease-1",
                NOW.plusSeconds(60), NOW, NOW);
    }

    private static Incident incident() {
        return new Incident(
                "incident-1", "key-1", "device-1",
                new DeviceDescriptor(DeviceFamily.ICM, "DEMO_ICM_V1", "1.2.0", 120),
                IncidentType.SCHEDULED_GATEWAY_TRANSMISSION_MISSED,
                IncidentSeverity.UNASSESSED, IncidentStatus.DETECTED,
                "monitoring-1", NOW, NOW, ProfileResolutionStatus.SUPPORTED,
                "demo-icm-v1", 0, NOW, NOW, null);
    }

    private static final class RecordingStore implements AgentRunStore {
        private final boolean rejectFailureWrite;
        private boolean claimed;
        private int failureAttempts;
        private String failedLeaseToken;
        private String failureCode;

        private RecordingStore(boolean rejectFailureWrite) {
            this.rejectFailureWrite = rejectFailureWrite;
        }

        @Override
        public Optional<AgentRun> claimNextRunnableRun(AgentRunClaimPolicy policy) {
            if (claimed) return Optional.empty();
            claimed = true;
            return Optional.of(run());
        }

        @Override public Optional<AgentRun> findRun(String runId) { return Optional.of(run()); }
        @Override public boolean renewLease(String runId, String leaseToken, Duration duration) { return true; }
        @Override public void markCompleted(String runId, String leaseToken) { }

        @Override
        public void markFailed(String runId, String leaseToken, String errorCode) {
            failureAttempts++;
            if (rejectFailureWrite) throw new IllegalStateException("AGENT_RUN_FAIL_CONFLICT");
            failedLeaseToken = leaseToken;
            failureCode = errorCode;
        }
    }

    private static final class RecordingHeartbeat implements AgentRunLeaseHeartbeat {
        private boolean closed;
        @Override
        public LeaseHandle start(AgentRun run) {
            return new LeaseHandle() {
                @Override public void assertLeaseActive() { }
                @Override public boolean leaseLost() { return false; }
                @Override public void close() { closed = true; }
            };
        }
    }

    private static final class RecordingObserver implements AgentRunExecutionObserver {
        private boolean failed;
        private boolean leaseLost;
        @Override public void claimed(AgentRun run) { }
        @Override public void completed(AgentRun run, Duration duration) { }
        @Override public void failed(AgentRun run, String errorCode, Duration duration) { failed = true; }
        @Override public void leaseLost(AgentRun run, Duration duration) { leaseLost = true; }
    }

    private static final class SingleIncidentQueries implements IncidentQueryUseCase {
        @Override public IncidentPage list(IncidentQuery query) { throw new UnsupportedOperationException(); }
        @Override public Optional<Incident> findById(String incidentId) { return Optional.of(incident()); }
        @Override public List<IncidentEvidence> listEvidence(String incidentId) { return List.of(); }
        @Override public List<AgentRun> listRuns(String incidentId) { return List.of(); }
    }

    private static final class FailingHandler implements AgentRunHandler {
        @Override public boolean supports(IncidentType incidentType) { return true; }
        @Override public void handle(AgentRun run, AgentRunLeaseGuard leaseGuard) {
            throw new IllegalStateException("simulated");
        }
    }

    private static final class LeaseLostHandler implements AgentRunHandler {
        @Override public boolean supports(IncidentType incidentType) { return true; }
        @Override public void handle(AgentRun run, AgentRunLeaseGuard leaseGuard) {
            throw new AgentRunLeaseLostException("AGENT_RUN_LEASE_LOST");
        }
    }
}
