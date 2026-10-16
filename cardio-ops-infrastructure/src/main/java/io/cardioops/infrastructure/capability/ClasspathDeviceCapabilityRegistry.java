package io.cardioops.infrastructure.capability;

import io.cardioops.domain.agent.ToolCapability;
import io.cardioops.domain.device.DeviceCapabilityProfile;
import io.cardioops.domain.device.DeviceCapabilityRegistry;
import io.cardioops.domain.device.DeviceFamily;
import io.cardioops.domain.device.FirmwareNumberRange;
import org.springframework.core.io.Resource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 从版本化 JSON 装设备档案。新型号通常改配置，不在图里加分支。目录是空的就启动失败，免得所有设备都变成不支持还看不出来。
 * 档案版本号记在快照里做审计。恢复时对的是 profileId，不是“文件今天又改了一版所以旧单不能跑”。
 */
public final class ClasspathDeviceCapabilityRegistry implements DeviceCapabilityRegistry {

    private final List<DeviceCapabilityProfile> profiles;
    private final String version;

    public ClasspathDeviceCapabilityRegistry(ObjectMapper objectMapper, Resource resource) {
        this(objectMapper, resource, versionFrom(resource));
    }

    public ClasspathDeviceCapabilityRegistry(ObjectMapper objectMapper, Resource resource, String version) {
        this.version = version;
        try (var inputStream = resource.getInputStream()) {
            var definitions = objectMapper.readValue(inputStream, new TypeReference<List<ProfileDefinition>>() { });
            this.profiles = definitions.stream().map(ProfileDefinition::toDomain).toList();
        } catch (IOException exception) {
            throw new IllegalStateException("cannot load device capability profiles from " + resource, exception);
        }
        if (profiles.isEmpty()) {
            throw new IllegalStateException("device capability registry must not be empty");
        }
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public List<DeviceCapabilityProfile> profiles() {
        return profiles;
    }

    private static String versionFrom(Resource resource) {
        String filename = resource.getFilename();
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("device profile resource must have a versioned filename");
        }
        return filename.endsWith(".json") ? filename.substring(0, filename.length() - 5) : filename;
    }

    private record ProfileDefinition(
            String profileId,
            DeviceFamily family,
            String modelCode,
            long firmwareMinInclusive,
            long firmwareMaxExclusive,
            int priority,
            Instant effectiveFrom,
            Instant effectiveUntil,
            Set<ToolCapability> allowedTools,
            Set<String> sopTags,
            boolean writeActionsAllowed
    ) {
        DeviceCapabilityProfile toDomain() {
            return new DeviceCapabilityProfile(
                    profileId,
                    family,
                    modelCode,
                    new FirmwareNumberRange(firmwareMinInclusive, firmwareMaxExclusive),
                    priority,
                    effectiveFrom,
                    effectiveUntil,
                    allowedTools,
                    sopTags,
                    writeActionsAllowed
            );
        }
    }
}
