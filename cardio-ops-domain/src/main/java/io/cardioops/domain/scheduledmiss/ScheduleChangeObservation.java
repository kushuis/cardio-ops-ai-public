package io.cardioops.domain.scheduledmiss;

import java.time.Instant;
import java.util.Objects;

/**
 * 云端看得见的一次 Web 广播时间修改。状态是成功时必须有完成时间，不然不能拿来证明旧应传时刻已经被改掉。
 */
public record ScheduleChangeObservation(
        String evidenceRef,
        ScheduleChangeStatus status,
        Instant submittedAt,
        Instant completedAt,
        Instant beforeBroadcastAt,
        Instant targetBroadcastAt
) {
    public ScheduleChangeObservation {
        Objects.requireNonNull(evidenceRef, "evidenceRef must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(submittedAt, "submittedAt must not be null");
        Objects.requireNonNull(beforeBroadcastAt, "beforeBroadcastAt must not be null");
        Objects.requireNonNull(targetBroadcastAt, "targetBroadcastAt must not be null");
        if (status == ScheduleChangeStatus.SUCCEEDED && completedAt == null) {
            throw new IllegalArgumentException("successful schedule change must have completedAt");
        }
    }

    /**
     * 是否足以证明旧应传时间在到来之前已经被成功替换。
     * 到了旧应传时间之后才完成的修改不能倒推旧窗口无效。
     */
    public boolean invalidatesOldExpectedTime(Instant oldExpectedAt) {
        return status == ScheduleChangeStatus.SUCCEEDED
                && !completedAt.isAfter(oldExpectedAt)
                && beforeBroadcastAt.equals(oldExpectedAt)
                && !targetBroadcastAt.equals(oldExpectedAt);
    }
}
