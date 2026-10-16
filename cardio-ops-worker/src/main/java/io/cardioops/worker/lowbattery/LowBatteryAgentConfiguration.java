package io.cardioops.worker.lowbattery;

import io.cardioops.agent.lowbattery.*;
import io.cardioops.agent.runtime.SpringAiToolCallingAgentLoop;
import io.cardioops.application.agentrun.AgentRunStore;
import io.cardioops.application.incident.IncidentQueryUseCase;
import io.cardioops.application.incident.IncidentStore;
import io.cardioops.application.lowbattery.*;
import io.cardioops.domain.agent.AgentApplicationRegistry;
import io.cardioops.domain.device.DeviceCapabilityRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/** 低电量场景自己的 Agent、Graph、Validator 和 Handler。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "cardio-ops.agent", name = "enabled", havingValue = "true")
public class LowBatteryAgentConfiguration {
    @Bean SpringAiLowBatteryAgent springAiLowBatteryAgent(
            SpringAiToolCallingAgentLoop runtime, Clock clock, MeterRegistry meters) {
        return new SpringAiLowBatteryAgent(runtime, clock, meters);
    }
    @Bean LowBatteryResultValidator lowBatteryResultValidator(Clock clock) {
        return new LowBatteryResultValidator(clock);
    }
    @Bean LowBatteryInvestigationGraph lowBatteryInvestigationGraph(
            AgentRunStore runs, LowBatteryInvestigationStore store, IncidentQueryUseCase incidents,
            DeviceCapabilityRegistry profiles, AgentApplicationRegistry applications,
            LowBatteryEvidenceSource evidenceSource, SpringAiLowBatteryAgent agent,
            LowBatteryResultValidator validator, ObjectMapper objectMapper,
            BaseCheckpointSaver saver, Clock clock, ObservationRegistry observations
    ) throws GraphStateException {
        return new LowBatteryInvestigationGraph(runs, store, incidents, profiles, applications,
                evidenceSource, agent, validator, objectMapper, saver, clock, observations);
    }
    @Bean LowBatteryAgentRunHandler lowBatteryAgentRunHandler(
            LowBatteryInvestigationCompletionUseCase completion, LowBatteryInvestigationGraph graph,
            IncidentStore incidents, Clock clock) {
        return new LowBatteryAgentRunHandler(completion, graph, incidents, clock);
    }
}
