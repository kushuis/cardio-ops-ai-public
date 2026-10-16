package io.cardioops.mock.tool;

record InvestigationScope(
        String incidentId,
        String correlationRef,
        String deviceRef,
        String traceId,
        String actorRole
) { }
