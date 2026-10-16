package io.cardioops.application.scheduledmiss;

import io.cardioops.domain.scheduledmiss.EvidenceUploadChannel;

import java.time.Instant;
import java.util.Objects;

/**
 * DevicePlatform 返回的原始传输批次。rawTriggerType 要在工具边界按冻住的档案翻译。模型看不到、也不解释这个整数。
 */
public record DevicePlatformTransmissionObservation(
        String evidenceRef,
        EvidenceUploadChannel channel,
        int rawTriggerType,
        Instant startedAt,
        Instant completedAt,
        int totalEventCount,
        int receivedEventCount,
        Instant deviceReportedNextBroadcastAt
) {
    public DevicePlatformTransmissionObservation {
        Objects.requireNonNull(evidenceRef, "evidenceRef must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt must not be before startedAt");
        }
        if (totalEventCount < 0 || receivedEventCount < 0 || receivedEventCount > totalEventCount) {
            throw new IllegalArgumentException("invalid event counts");
        }
    }
}
