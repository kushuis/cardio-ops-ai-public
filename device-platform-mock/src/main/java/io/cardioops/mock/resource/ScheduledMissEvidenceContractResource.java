package io.cardioops.mock.resource;

import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

/** Stable semantics advertised independently from instance-level evidence tools. */
@Component
public final class ScheduledMissEvidenceContractResource {

    public static final String URI = "device-platform://cardio-ops/evidence-contract/scheduled-miss/v1";

    @McpResource(
            uri = URI,
            name = "scheduled-miss-evidence-contract-v1",
            title = "Scheduled-miss evidence contract V1",
            description = "Semantics and safety boundaries for scheduled-miss evidence tools",
            mimeType = "application/json"
    )
    public String readContract() {
        return """
                {
                  "version": "1.0",
                  "scope": "A Device Platform-issued MONITORING correlation reference",
                  "semantics": {
                    "rawTriggerType": "Normalized only by the frozen device profile",
                    "currentGateway": "Must not replace gatewayAtExpected",
                    "manualTransmission": "Cannot prove automatic-wakeup recovery"
                  },
                  "privacy": "Synthetic data only; no patient identity or raw ECG"
                }
                """;
    }
}
