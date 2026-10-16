package io.cardioops.domain.scheduledmiss;

import java.time.Instant;
import java.util.Objects;

/**
 * 网关此刻的快照。它回答现在还离不离线，不能拿去替换应传那一刻的网关状态。
 */
public record GatewayStatusSnapshot(
        String evidenceRef,
        GatewayConnectivity connectivity,
        Instant observedAt,
        Instant lastOnlineAt,
        Instant lastOfflineAt
) {
    public GatewayStatusSnapshot {
        Objects.requireNonNull(evidenceRef, "evidenceRef must not be null");
        Objects.requireNonNull(connectivity, "connectivity must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
    }
}
