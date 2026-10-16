package io.cardioops.worker.scheduledmiss;

import io.cardioops.agent.scheduledmiss.ScheduledMissInvestigationGraph;
import io.cardioops.agent.scheduledmiss.ScheduledMissAgentRunHandler;
import io.cardioops.agent.scheduledmiss.ScheduledMissResultValidator;
import io.cardioops.agent.scheduledmiss.SpringAiScheduledMissAgent;
import io.cardioops.agent.runtime.SpringAiToolCallingAgentLoop;
import io.cardioops.application.incident.IncidentQueryUseCase;
import io.cardioops.application.agentrun.AgentRunStore;
import io.cardioops.application.scheduledmiss.ScheduledMissInvestigationStore;
import io.cardioops.application.scheduledmiss.ScheduledMissInvestigationCompletionUseCase;
import io.cardioops.application.scheduledmiss.ScheduledMissEvidenceSource;
import io.cardioops.application.knowledge.VersionedKnowledgeSearch;
import io.cardioops.domain.agent.AgentApplicationRegistry;
import io.cardioops.domain.device.DeviceCapabilityRegistry;
import io.cardioops.domain.scheduledmiss.IcmProtocolGeneration;
import io.cardioops.domain.scheduledmiss.IcmTransmissionTrigger;
import io.cardioops.domain.scheduledmiss.IcmTriggerPolicy;
import io.cardioops.domain.scheduledmiss.IcmTriggerPolicyRegistry;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * 应传未传的模型、校验、图和 Handler。只有 agent.enabled 才装配。
 * 图挂上持久化 Checkpoint，续的是崩溃后的执行线程，不是人工批准。触发码按档案翻译，不认识的不猜。
 */

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ScheduledMissAgentProperties.class)
@ConditionalOnProperty(prefix = "cardio-ops.agent", name = "enabled", havingValue = "true")
public class ScheduledMissAgentConfiguration {

    @Bean
    IcmTriggerPolicyRegistry icmTriggerPolicyRegistry() {
        // 规则来自已核对的 DevicePlatform IcmTriggerType/IcmTriggerTypeV2 与业务确认。
        // V1 的 0=复位广播已经废弃，所以不注册 0；未知值会安全降级而不是猜测。
        var v1 = new IcmTriggerPolicy("demo-icm-v1", IcmProtocolGeneration.V1, Map.of(
                1, IcmTransmissionTrigger.MAGNET,
                2, IcmTransmissionTrigger.SCHEDULED));
        var v2For1c = new IcmTriggerPolicy("demo-icm-v2", IcmProtocolGeneration.V2, Map.of(
                1, IcmTransmissionTrigger.MAGNET,
                2, IcmTransmissionTrigger.EVENT,
                3, IcmTransmissionTrigger.SCHEDULED,
                4, IcmTransmissionTrigger.ALERT));
        // DevicePlatform IcmTriggerTypeV2 已核对：1=磁铁、2=事件、3=家庭监护定时、4=告警。
        // profile demo-icm-v2-secure 精确覆盖合成型号 DEMO_ICM_V2_SECURE 的固件版本范围。
        var v2For2a = new IcmTriggerPolicy("demo-icm-v2-secure", IcmProtocolGeneration.V2,
                v2For1c.triggerMappings());
        return new IcmTriggerPolicyRegistry(List.of(v1, v2For1c, v2For2a));
    }

    @Bean
    SpringAiScheduledMissAgent springAiScheduledMissAgent(
            SpringAiToolCallingAgentLoop runtime,
            Clock clock,
            VersionedKnowledgeSearch knowledgeSearch,
            MeterRegistry meterRegistry
    ) {
        return new SpringAiScheduledMissAgent(runtime, clock, knowledgeSearch, meterRegistry);
    }

    @Bean
    ScheduledMissResultValidator scheduledMissResultValidator(
            ScheduledMissAgentProperties properties,
            Clock clock
    ) {
        return new ScheduledMissResultValidator(properties.scheduledTransmissionGrace(), clock);
    }

    @Bean
    ScheduledMissInvestigationGraph scheduledMissInvestigationGraph(
            AgentRunStore agentRunStore,
            ScheduledMissInvestigationStore investigationStore,
            IncidentQueryUseCase incidentQueries,
            DeviceCapabilityRegistry capabilityRegistry,
            AgentApplicationRegistry applicationRegistry,
            IcmTriggerPolicyRegistry triggerPolicyRegistry,
            ScheduledMissEvidenceSource evidenceSource,
            VersionedKnowledgeSearch knowledgeSearch,
            SpringAiScheduledMissAgent agent,
            ScheduledMissResultValidator validator,
            ObjectMapper objectMapper,
            BaseCheckpointSaver checkpointSaver,
            Clock clock,
            ObservationRegistry observationRegistry
    ) throws GraphStateException {
        return new ScheduledMissInvestigationGraph(
                agentRunStore, investigationStore, incidentQueries, capabilityRegistry, applicationRegistry,
                triggerPolicyRegistry, evidenceSource, knowledgeSearch, agent, validator,
                objectMapper, checkpointSaver, clock, observationRegistry);
    }

    @Bean
    ScheduledMissAgentRunHandler scheduledMissAgentRunHandler(
            ScheduledMissInvestigationCompletionUseCase completionUseCase,
            ScheduledMissInvestigationGraph graph
    ) {
        return new ScheduledMissAgentRunHandler(completionUseCase, graph);
    }
}
