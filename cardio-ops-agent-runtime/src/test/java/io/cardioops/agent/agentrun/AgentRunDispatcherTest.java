package io.cardioops.agent.agentrun;

import io.cardioops.application.incident.IncidentPage;
import io.cardioops.application.incident.IncidentQuery;
import io.cardioops.application.incident.IncidentQueryUseCase;
import io.cardioops.application.agentrun.AgentRunHandler;
import io.cardioops.application.agentrun.AgentRunLeaseGuard;
import io.cardioops.domain.agent.AgentRun;
import io.cardioops.domain.agent.AgentRunStatus;
import io.cardioops.domain.device.DeviceDescriptor;
import io.cardioops.domain.device.DeviceFamily;
import io.cardioops.domain.incident.Incident;
import io.cardioops.domain.incident.IncidentEvidence;
import io.cardioops.domain.incident.IncidentSeverity;
import io.cardioops.domain.incident.IncidentStatus;
import io.cardioops.domain.incident.IncidentType;
import io.cardioops.domain.device.ProfileResolutionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证公共 Dispatcher 按案件类型路由，而不是写死调用应传未传 Graph。 */
class AgentRunDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-09-06T10:00:00Z");

    @Test
    void routesRunToTheOnlyHandlerSupportingItsIncidentType() {
        var scheduledMissHandler = new RecordingHandler(IncidentType.SCHEDULED_GATEWAY_TRANSMISSION_MISSED);
        var lowBatteryHandler = new RecordingHandler(IncidentType.DEVICE_LOW_BATTERY);
        var dispatcher = new AgentRunDispatcher(
                new SingleIncidentQuery(incident(IncidentType.SCHEDULED_GATEWAY_TRANSMISSION_MISSED)),
                List.of(lowBatteryHandler, scheduledMissHandler));

        dispatcher.dispatch(run(), AgentRunLeaseGuard.alwaysActive());

        assertThat(scheduledMissHandler.handledRunId).isEqualTo("run-1");
        assertThat(lowBatteryHandler.handledRunId).isNull();
    }

    @Test
    void rejectsRunWhenNoBusinessHandlerHasBeenRegistered() {
        var dispatcher = new AgentRunDispatcher(
                new SingleIncidentQuery(incident(IncidentType.DEVICE_LOW_BATTERY)),
                List.of(new RecordingHandler(IncidentType.SCHEDULED_GATEWAY_TRANSMISSION_MISSED)));

        assertThatThrownBy(() -> dispatcher.dispatch(run(), AgentRunLeaseGuard.alwaysActive()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AGENT_RUN_HANDLER_NOT_FOUND");
    }

    private static AgentRun run() {
        return new AgentRun("run-1", "incident-1", "event-1", "thread-1",
                AgentRunStatus.RUNNING, "demo-icm-v2", "trace-1", 1,
                "worker-1", "lease-1", NOW.plusSeconds(60), NOW, NOW);
    }

    private static Incident incident(IncidentType type) {
        var device = new DeviceDescriptor(DeviceFamily.ICM, "ICM-1C", "1.0.0", 100L);
        return new Incident("incident-1", "business-key", "device-1", device, type,
                IncidentSeverity.UNASSESSED, IncidentStatus.DETECTED, "correlation-1", NOW, NOW,
                ProfileResolutionStatus.SUPPORTED, "demo-icm-v2", 0L, NOW, NOW, null);
    }

    private static final class RecordingHandler implements AgentRunHandler {
        private final IncidentType supportedType;
        private String handledRunId;

        private RecordingHandler(IncidentType supportedType) {
            this.supportedType = supportedType;
        }

        @Override
        public boolean supports(IncidentType incidentType) {
            return supportedType == incidentType;
        }

        @Override
        public void handle(AgentRun run, AgentRunLeaseGuard leaseGuard) {
            handledRunId = run.runId();
        }
    }

    private record SingleIncidentQuery(Incident incident) implements IncidentQueryUseCase {
        @Override public IncidentPage list(IncidentQuery query) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<Incident> findById(String incidentId) {
            return Optional.of(incident);
        }
        @Override public List<IncidentEvidence> listEvidence(String incidentId) {
            throw new UnsupportedOperationException();
        }
        @Override public List<AgentRun> listRuns(String incidentId) {
            throw new UnsupportedOperationException();
        }
    }
}
