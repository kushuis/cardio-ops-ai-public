package io.cardioops.domain.device;

import java.util.List;

/**
 * 读 Profile 的端口。领域层只依赖它，不关心数据来自 classpath JSON 还是别的地方。
 */
public interface DeviceCapabilityRegistry {
    /**
     * 整份 Profile 目录的不可变发布版本，用于 AgentRun 策略快照。
     *
     * <p>测试或内存实现可以只提供 {@link #profiles()}；生产 Classpath 实现会覆盖为资源版本。</p>
     */
    default String version() {
        return "unversioned";
    }

    /** 返回当前可参与解析的全部版本化 Profile；实现必须保证集合本身不可被外部修改。 */
    List<DeviceCapabilityProfile> profiles();
}
