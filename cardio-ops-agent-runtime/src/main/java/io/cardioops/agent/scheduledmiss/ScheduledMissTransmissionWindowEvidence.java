package io.cardioops.agent.scheduledmiss;

import io.cardioops.domain.scheduledmiss.TransmissionObservation;

import java.util.List;

/**
 * 提供给模型的传输窗口。触发原因已按设备档案归一化；未知原始码进入 ignored，禁止模型自行解释。刚好满一页不视为截断。
 */
public record ScheduledMissTransmissionWindowEvidence(
        List<TransmissionObservation> transmissions,
        List<Integer> ignoredRawTriggerTypes,
        boolean truncated
) {
    public ScheduledMissTransmissionWindowEvidence {
        transmissions = List.copyOf(transmissions);
        ignoredRawTriggerTypes = List.copyOf(ignoredRawTriggerTypes);
    }
}
