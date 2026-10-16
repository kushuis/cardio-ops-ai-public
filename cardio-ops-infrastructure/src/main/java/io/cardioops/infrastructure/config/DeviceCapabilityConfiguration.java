package io.cardioops.infrastructure.config;

import io.cardioops.domain.agent.AgentApplicationCode;
import io.cardioops.domain.agent.AgentApplicationDefinition;
import io.cardioops.domain.agent.AgentApplicationRegistry;
import io.cardioops.domain.agent.ToolCapability;
import io.cardioops.domain.device.DeviceCapabilityRegistry;
import io.cardioops.domain.device.InvestigationProfileResolver;
import io.cardioops.infrastructure.capability.ClasspathDeviceCapabilityRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 设备档案的装配跟 Kafka 开不开无关。API 也要能解释一台设备为什么落到这份 Profile。
 */

@Configuration(proxyBeanMethods = false)
public class DeviceCapabilityConfiguration {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    ObjectMapper objectMapper() {
        // 非 Web Worker 不一定触发 Jackson Web 自动配置，因此提供一个可被 Boot 覆盖的默认 Bean。
        return JsonMapper.builder().findAndAddModules().build();
    }

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock systemClock() {
        // 业务代码依赖 Clock 而不是 Instant.now()，测试可以替换为固定时间。
        return Clock.systemUTC();
    }

    @Bean
    DeviceCapabilityRegistry deviceCapabilityRegistry(ObjectMapper objectMapper) {
        return new ClasspathDeviceCapabilityRegistry(
                objectMapper, new ClassPathResource("device-profiles-v1.json"));
    }

    @Bean
    InvestigationProfileResolver investigationProfileResolver(DeviceCapabilityRegistry registry) {
        return new InvestigationProfileResolver(registry);
    }

    @Bean
    AgentApplicationRegistry agentApplicationRegistry() {
        var readOnlyTools = Set.of(
                ToolCapability.GET_DEVICE_CONTEXT,
                ToolCapability.GET_TRANSMISSION_HISTORY,
                ToolCapability.GET_SUPPORTED_PROFILE_HELP
        );
        return new AgentApplicationRegistry(List.of(
                new AgentApplicationDefinition(
                        AgentApplicationCode.OPS_INVESTIGATOR,
                        Set.of("OPS_AGENT", "OPS_ADMIN"),
                        EnumSet.allOf(ToolCapability.class),
                        true),
                // 患者端和医生端先登记边界但不启用，避免未来直接复用运营 Agent 的高权限工具。
                new AgentApplicationDefinition(
                        AgentApplicationCode.DOCTOR_COPILOT,
                        Set.of("DOCTOR"), readOnlyTools, false),
                new AgentApplicationDefinition(
                        AgentApplicationCode.PATIENT_ASSISTANT,
                        Set.of("PATIENT"), readOnlyTools, false)
        ));
    }
}
