package io.cardioops.agent.scheduledmiss;

import io.cardioops.application.scheduledmiss.DevicePlatformTransmissionObservation;
import io.cardioops.domain.scheduledmiss.EvidenceUploadChannel;
import io.cardioops.domain.scheduledmiss.IcmProtocolGeneration;
import io.cardioops.domain.scheduledmiss.IcmTransmissionTrigger;
import io.cardioops.domain.scheduledmiss.IcmTriggerPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 复查窗口变长后，账本保留最近的传输，不能拿最早的 20 条下结论。 */
class ScheduledMissTransmissionSelectionTest {

    private static final Instant START = Instant.parse("2026-09-06T01:00:00Z");
    private static final IcmTriggerPolicy POLICY = new IcmTriggerPolicy(
            "demo-icm-v2", IcmProtocolGeneration.V2, Map.of(
            1, IcmTransmissionTrigger.MAGNET,
            2, IcmTransmissionTrigger.EVENT,
            3, IcmTransmissionTrigger.SCHEDULED,
            4, IcmTransmissionTrigger.ALERT));

    @Test
    void keepsTheNewestTransmissionsInsteadOfTheOldest() {
        var raw = new ArrayList<DevicePlatformTransmissionObservation>();
        for (int index = 0; index < 25; index++) {
            raw.add(transmission("tx-" + index, EvidenceUploadChannel.GATEWAY, 2,
                    START.plusSeconds(index * 60L)));
        }

        List<DevicePlatformTransmissionObservation> kept =
                ScheduledMissToolGateway.keepNewestForLedger(raw, 20, POLICY);

        assertThat(kept).hasSize(20);
        assertThat(kept.getFirst().evidenceRef()).isEqualTo("tx-5");
        assertThat(kept.getLast().evidenceRef()).isEqualTo("tx-24");
    }

    @Test
    void keepsALaterScheduledGatewayTransmissionWhenNewerEventsFillTheLedger() {
        var raw = new ArrayList<DevicePlatformTransmissionObservation>();
        raw.add(transmission("tx-scheduled", EvidenceUploadChannel.GATEWAY, 3, START.plusSeconds(120)));
        for (int index = 0; index < 20; index++) {
            raw.add(transmission("tx-event-" + index, EvidenceUploadChannel.GATEWAY, 2,
                    START.plusSeconds(300 + index * 60L)));
        }

        List<DevicePlatformTransmissionObservation> kept =
                ScheduledMissToolGateway.keepNewestForLedger(raw, 20, POLICY);

        assertThat(kept).extracting(DevicePlatformTransmissionObservation::evidenceRef).contains("tx-scheduled");
        assertThat(kept).hasSize(20);
    }

    private static DevicePlatformTransmissionObservation transmission(
            String ref,
            EvidenceUploadChannel channel,
            int trigger,
            Instant completedAt
    ) {
        return new DevicePlatformTransmissionObservation(
                ref, channel, trigger, completedAt.minusSeconds(30), completedAt, 1, 1, null);
    }
}
