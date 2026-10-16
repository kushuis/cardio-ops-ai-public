package io.cardioops.domain.agent;

import java.util.Objects;
import java.util.Set;

/**
 * 一个入口允许哪些角色、最多能用哪些工具。禁用的入口不该被调用方当成“先用着”。
 */
public record AgentApplicationDefinition(
        AgentApplicationCode code,
        Set<String> allowedRoles,
        Set<ToolCapability> toolAllowlist,
        boolean enabled
) {
    public AgentApplicationDefinition {
        Objects.requireNonNull(code, "code must not be null");
        allowedRoles = Set.copyOf(allowedRoles);
        toolAllowlist = Set.copyOf(toolAllowlist);
    }
}
