package io.cardioops.agent.scheduledmiss;

import io.cardioops.domain.scheduledmiss.IcmProtocolGeneration;
import io.cardioops.domain.scheduledmiss.IcmTransmissionTrigger;
import io.cardioops.domain.scheduledmiss.IcmTriggerPolicy;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 用户确认的 V1/V2 原始触发码口径，必须用单元测试锁死，不能交给模型猜。 */
class IcmTriggerPolicyTest {

    @Test
    void v1IgnoresDeprecatedResetAndOnlyScheduledBroadcastProvesAutomaticWakeup() {
        var policy = new IcmTriggerPolicy("icm-v1", IcmProtocolGeneration.V1, Map.of(
                1, IcmTransmissionTrigger.MAGNET,
                2, IcmTransmissionTrigger.SCHEDULED));

        assertThat(policy.normalize(0)).isEmpty();
        assertThat(policy.normalize(1)).contains(IcmTransmissionTrigger.MAGNET);
        assertThat(policy.normalize(1).orElseThrow().provesAutomaticWakeup()).isFalse();
        assertThat(policy.normalize(2)).contains(IcmTransmissionTrigger.SCHEDULED);
        assertThat(policy.normalize(2).orElseThrow().provesAutomaticWakeup()).isTrue();
    }

    @Test
    void v2TreatsEventScheduledAndAlertAsAutomaticButMagnetAsManual() {
        var policy = new IcmTriggerPolicy("icm-v2", IcmProtocolGeneration.V2, Map.of(
                1, IcmTransmissionTrigger.MAGNET,
                2, IcmTransmissionTrigger.EVENT,
                3, IcmTransmissionTrigger.SCHEDULED,
                4, IcmTransmissionTrigger.ALERT));

        assertThat(policy.normalize(1).orElseThrow().provesAutomaticWakeup()).isFalse();
        assertThat(policy.normalize(2).orElseThrow().provesAutomaticWakeup()).isTrue();
        assertThat(policy.normalize(3).orElseThrow().provesAutomaticWakeup()).isTrue();
        assertThat(policy.normalize(4).orElseThrow().provesAutomaticWakeup()).isTrue();
        assertThat(policy.normalize(99)).isEmpty();
    }
}
