package io.cardioops.mock.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevicePlatformEvidenceToolsTest {

    private final DevicePlatformEvidenceTools tools = new DevicePlatformEvidenceTools("SYSTEM_WORKER");

    @Test
    void returnsSyntheticScheduledMissEvidenceInsideBoundDeviceScope() {
        var context = tools.scheduledMissContext(
                "incident-000000000000000000000000001",
                "MONITORING:DEMO-ICM-001:1768464000",
                "DEMO-ICM-001",
                "trace-demo-001",
                "SYSTEM_WORKER");

        assertThat(context.deviceRef()).isEqualTo("DEMO-ICM-001");
        assertThat(context.device().modelCode()).isEqualTo("DEMO_ICM_V2_SECURE");
        assertThat(tools.scheduledMissTransmissions(
                "incident-000000000000000000000000001",
                "MONITORING:DEMO-ICM-001:1768464000",
                "DEMO-ICM-001",
                "trace-demo-001",
                "SYSTEM_WORKER")).hasSize(1);
    }

    @Test
    void rejectsCrossDeviceEvidenceAccess() {
        assertThatThrownBy(() -> tools.scheduledMissContext(
                "incident-000000000000000000000000001",
                "MONITORING:DEMO-ICM-001:1768464000",
                "DEMO-ICM-999",
                "trace-demo-001",
                "SYSTEM_WORKER"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("DEVICE_SCOPE_VIOLATION");
    }
}
