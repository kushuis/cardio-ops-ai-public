package io.cardioops.domain.scheduledmiss;

import java.time.Instant;
import java.util.Objects;

/**
 * 一条已经按当前档案翻译过的传输。完成时间不能早于开始时间，事件计数也不能自相矛盾。
 * 渠道不能代替触发原因。只有触发原因上标记了自动唤醒，这条才算恢复证据。
 */
public record TransmissionObservation(
        String evidenceRef,
        EvidenceUploadChannel channel,
        IcmTransmissionTrigger trigger,
        Instant startedAt,
        Instant completedAt,
        int totalEventCount,
        int receivedEventCount,
        Instant deviceReportedNextBroadcastAt
) {
    public TransmissionObservation {
        Objects.requireNonNull(evidenceRef, "evidenceRef must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(trigger, "trigger must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt must not be before startedAt");
        }
        if (totalEventCount < 0 || receivedEventCount < 0 || receivedEventCount > totalEventCount) {
            throw new IllegalArgumentException("invalid event counts");
        }
    }

    /** 手动/自动触发与渠道同时满足时，才能作为自动唤醒恢复证据。 */
    public boolean provesAutomaticWakeup() {
        return channel == EvidenceUploadChannel.GATEWAY
                && trigger.provesAutomaticWakeup();
    }
}
