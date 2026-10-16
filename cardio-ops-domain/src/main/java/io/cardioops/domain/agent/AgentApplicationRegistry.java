package io.cardioops.domain.agent;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 启动时按入口代号收齐权限定义。同一个代号出现两次就直接失败，不然权限会跟着加载顺序变。
 * 查的时候，禁用和不存在都当没有。调用方不能拿一份关掉的定义先跑起来。
 *
 *
 * 留最后一份，权限取决于谁后加载。启动失败才能让配置冲突在上线前暴露。
 */
public final class AgentApplicationRegistry {

    private final Map<AgentApplicationCode, AgentApplicationDefinition> definitions;

    public AgentApplicationRegistry(List<AgentApplicationDefinition> definitions) {
        var indexed = new EnumMap<AgentApplicationCode, AgentApplicationDefinition>(AgentApplicationCode.class);
        for (var definition : definitions) {
            // 重复 code 会导致权限定义取决于加载顺序，因此启动时直接失败。
            if (indexed.put(definition.code(), definition) != null) {
                throw new IllegalArgumentException("duplicate agent application: " + definition.code());
            }
        }
        this.definitions = Map.copyOf(indexed);
    }

    /**
     * 禁用和不存在都返回空。调用方不能把关掉的入口当成还能用。
     */
    public Optional<AgentApplicationDefinition> findEnabled(AgentApplicationCode code) {
        // disabled 与不存在都不返回定义，调用方不能“先用起来再说”。
        return Optional.ofNullable(definitions.get(code)).filter(AgentApplicationDefinition::enabled);
    }
}
