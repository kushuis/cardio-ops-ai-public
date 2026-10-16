package io.cardioops.mock.tool;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;

/** Enforces role, correlation-key format and device isolation again on the server side. */
final class InvestigationScopeValidator {

    private static final Pattern SCHEDULED_MISS =
            Pattern.compile("^MONITORING:([^:]{1,128}):(\\d{1,19})$");
    private static final Pattern LOW_BATTERY =
            Pattern.compile("^BATTERY:([^:]{1,128}):ICM_PATIENT_SERVICE:(\\d{1,19})$");
    private static final Set<String> READ_ROLES = Set.of("SYSTEM_WORKER", "OPS_REVIEWER", "SUPERVISOR");
    private final String authenticatedRole;

    InvestigationScopeValidator(String authenticatedRole) {
        this.authenticatedRole = authenticatedRole;
    }

    InvestigationScope scheduledMiss(InvestigationScope scope) {
        var validated = validateCommon(scope, SCHEDULED_MISS);
        try {
            Instant.ofEpochSecond(Long.parseLong(SCHEDULED_MISS.matcher(scope.correlationRef()).results()
                    .findFirst().orElseThrow().group(2)));
        } catch (NumberFormatException | DateTimeException invalidTime) {
            throw new IllegalArgumentException("CORRELATION_REF_INVALID", invalidTime);
        }
        return validated;
    }

    InvestigationScope lowBattery(InvestigationScope scope) {
        return validateCommon(scope, LOW_BATTERY);
    }

    private InvestigationScope validateCommon(InvestigationScope scope, Pattern pattern) {
        if (scope == null) {
            throw new IllegalArgumentException("MCP_SCOPE_REQUIRED");
        }
        required(scope.incidentId(), 36, "INCIDENT_ID_INVALID");
        required(scope.traceId(), 128, "TRACE_ID_INVALID");
        required(scope.deviceRef(), 128, "DEVICE_REF_INVALID");
        if (!READ_ROLES.contains(scope.actorRole()) || !authenticatedRole.equals(scope.actorRole())) {
            throw new IllegalArgumentException("ACTOR_ROLE_FORBIDDEN");
        }
        var matcher = pattern.matcher(scope.correlationRef() == null ? "" : scope.correlationRef());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("CORRELATION_REF_INVALID");
        }
        if (!matcher.group(1).equals(scope.deviceRef())) {
            throw new IllegalArgumentException("DEVICE_SCOPE_VIOLATION");
        }
        return scope;
    }

    private void required(String value, int maximumLength, String errorCode) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(errorCode);
        }
    }
}
