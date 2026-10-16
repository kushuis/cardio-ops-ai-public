package io.cardioops.application.integrationevent;

import io.cardioops.domain.device.ProfileResolutionStatus;

/**
 * 进门的结果，给日志和指标用。只有新接受的那次才有 intakeId。重复信封和重复事实要能分开。
 */
public record IntegrationIntakeResult(
        String eventId,
        IntegrationIntakeStatus status,
        String intakeId,
        ProfileResolutionStatus profileStatus,
        String profileId
) {
}
