package io.cardioops.worker.agentrun;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 公共 Agent 模型运行时配置；不包含任何具体业务场景参数。 */
@ConfigurationProperties("cardio-ops.agent")
public record AgentRuntimeProperties(
        String modelName
) {
    public AgentRuntimeProperties {
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("model-name must not be blank");
        }
    }
}
