package io.cardioops.mock.resource;

import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

/** Stable low-battery semantics without any instance-level data. */
@Component
public final class LowBatteryEvidenceContractResource {

    public static final String URI = "device-platform://cardio-ops/evidence-contract/low-battery/v1";

    @McpResource(
            uri = URI,
            name = "low-battery-evidence-contract-v1",
            title = "Low-battery evidence contract V1",
            description = "Semantics and safety boundaries for low-battery evidence tools",
            mimeType = "application/json"
    )
    public String readContract() {
        return """
                {
                  "version": "1.0",
                  "scope": "A Device Platform-issued BATTERY correlation reference",
                  "semantics": {
                    "causality": "Correlation does not prove that low battery caused transmission failure",
                    "decision": "The Java validator computes the final impact assessment"
                  },
                  "privacy": "Synthetic data only; no patient identity or raw ECG"
                }
                """;
    }
}
