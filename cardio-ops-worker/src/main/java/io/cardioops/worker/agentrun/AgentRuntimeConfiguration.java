package io.cardioops.worker.agentrun;

import io.cardioops.agent.runtime.SpringAiToolCallingAgentLoop;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 所有业务场景 Agent 共用的模型调用运行时。
 *
 * <p>它只处理工具循环、预算、租约检查和指标，不包含应传未传或低电量规则。
 * 业务提示词、可用工具、Graph 与 Validator 仍由各场景单独提供。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentRuntimeProperties.class)
@ConditionalOnProperty(prefix = "cardio-ops.agent", name = "enabled", havingValue = "true")
public class AgentRuntimeConfiguration {

    @Bean
    SpringAiToolCallingAgentLoop springAiToolCallingAgentLoop(
            ChatModel chatModel,
            AgentRuntimeProperties properties,
            MeterRegistry meterRegistry
    ) {
        return new SpringAiToolCallingAgentLoop(chatModel, properties.modelName(), meterRegistry);
    }
}
