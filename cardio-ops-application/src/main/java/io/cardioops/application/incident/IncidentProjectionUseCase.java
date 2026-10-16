package io.cardioops.application.incident;

import io.cardioops.application.integrationevent.IntegrationEventCommand;
import io.cardioops.domain.device.ResolvedInvestigationProfile;

/**
 * 进门两道幂等通过之后，把事件收成案件、证据，需要时再排一条 AgentRun。
 */

@FunctionalInterface
public interface IncidentProjectionUseCase {
    IncidentProjectionResult project(IntegrationEventCommand command, ResolvedInvestigationProfile profile);
}
