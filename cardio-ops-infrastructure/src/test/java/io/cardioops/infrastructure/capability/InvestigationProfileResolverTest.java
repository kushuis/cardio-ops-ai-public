package io.cardioops.infrastructure.capability;

import io.cardioops.domain.device.DeviceCapabilityProfile;
import io.cardioops.domain.device.DeviceDescriptor;
import io.cardioops.domain.device.DeviceFamily;
import io.cardioops.domain.device.FirmwareNumberRange;
import io.cardioops.domain.device.InvestigationProfileResolver;
import io.cardioops.domain.device.ProfileResolutionStatus;
import io.cardioops.domain.agent.ToolCapability;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InvestigationProfileResolverTest {

    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");

    @Test
    void resolvesDifferentProfilesForIcmAndIcd() {
        var resolver = new InvestigationProfileResolver(registryFromJson());

        var icm = resolver.resolve(device(DeviceFamily.ICM, "DEMO_ICM_V1", 120), NOW);
        var icd = resolver.resolve(device(DeviceFamily.ICD, "DEMO_ICD_V1", 120), NOW);

        assertThat(icm.status()).isEqualTo(ProfileResolutionStatus.SUPPORTED);
        assertThat(icm.profileId()).isEqualTo("demo-icm-v1");
        assertThat(icd.profileId()).isEqualTo("demo-icd-v1");
        assertThat(icd.allowedTools()).contains(ToolCapability.GET_PARAMETER_READBACK);
        assertThat(icm.allowedTools()).doesNotContain(ToolCapability.GET_PARAMETER_READBACK);
    }

    @Test
    void unknownFirmwareFallsBackToSafeReadOnlyTools() {
        var result = new InvestigationProfileResolver(registryFromJson())
                .resolve(device(DeviceFamily.ICM, "DEMO_ICM_V1", 999), NOW);

        assertThat(result.status()).isEqualTo(ProfileResolutionStatus.UNSUPPORTED_PROFILE);
        assertThat(result.profileId()).isNull();
        assertThat(result.writeActionsAllowed()).isFalse();
        assertThat(result.allowedTools()).containsExactlyInAnyOrder(
                ToolCapability.GET_DEVICE_CONTEXT,
                ToolCapability.GET_SUPPORTED_PROFILE_HELP);
    }

    @Test
    void resolvesSmIcm10Version110ToTheVerifiedV2Profile() {
        // DevicePlatform IcmTriggerTypeV2 定义了此设备采用的 V2 广播触发语义；
        // 17408 是测试环境上游事件中 1.1.0 对应的 firmware_version_number。
        var result = new InvestigationProfileResolver(registryFromJson())
                .resolve(device(DeviceFamily.ICM, "DEMO_ICM_V2_SECURE", 17408), NOW);

        assertThat(result.status()).isEqualTo(ProfileResolutionStatus.SUPPORTED);
        assertThat(result.profileId()).isEqualTo("demo-icm-v2-secure");
        assertThat(result.allowedTools()).containsExactlyInAnyOrder(
                ToolCapability.GET_SCHEDULED_MISS_CONTEXT,
                ToolCapability.QUERY_TRANSMISSIONS_IN_WINDOW,
                ToolCapability.GET_CURRENT_GATEWAY_SNAPSHOT,
                ToolCapability.QUERY_SCHEDULE_CHANGE_HISTORY,
                ToolCapability.SEARCH_VERSIONED_KNOWLEDGE,
                ToolCapability.GET_DEVICE_CONTEXT,
                ToolCapability.GET_GATEWAY_SNAPSHOT,
                ToolCapability.GET_TRANSMISSION_HISTORY);
    }

    @Test
    void equalPriorityAndSpecificityIsRejectedAsAmbiguous() {
        var resolver = new InvestigationProfileResolver(() -> List.of(profile("first"), profile("second")));

        var result = resolver.resolve(device(DeviceFamily.ICM, "DEMO_ICM_V1", 120), NOW);

        assertThat(result.status()).isEqualTo(ProfileResolutionStatus.AMBIGUOUS_PROFILE);
        assertThat(result.writeActionsAllowed()).isFalse();
    }

    private ClasspathDeviceCapabilityRegistry registryFromJson() {
        return new ClasspathDeviceCapabilityRegistry(
                JsonMapper.builder().findAndAddModules().build(),
                new ClassPathResource("device-profiles-v1.json"));
    }

    private DeviceDescriptor device(DeviceFamily family, String modelCode, long firmwareNumber) {
        return new DeviceDescriptor(family, modelCode, "1.2.0", firmwareNumber);
    }

    private DeviceCapabilityProfile profile(String id) {
        return new DeviceCapabilityProfile(
                id, DeviceFamily.ICM, "DEMO_ICM_V1", new FirmwareNumberRange(100, 200),
                100, Instant.parse("2026-01-01T00:00:00Z"), null,
                Set.of(ToolCapability.GET_DEVICE_CONTEXT), Set.of("icm"), false);
    }
}
