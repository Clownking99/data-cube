package com.datacube.service;

import java.util.List;
import java.util.Objects;

/** Fixed/redacted confirmation requirements for one exact rendered schema plan. */
public record SchemaDeploymentAdmission(
        String planDigest,
        boolean confirmationRequired,
        boolean effectiveDestructive,
        boolean safetyEscalated,
        boolean productionEscalated,
        List<String> warnings) {
    public SchemaDeploymentAdmission {
        planDigest = Objects.requireNonNull(planDigest, "planDigest");
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }

    @Override
    public String toString() {
        return "SchemaDeploymentAdmission[confirmationRequired=" + confirmationRequired
                + ", effectiveDestructive=" + effectiveDestructive
                + ", safetyEscalated=" + safetyEscalated
                + ", productionEscalated=" + productionEscalated
                + ", warningCount=" + warnings.size() + "]";
    }
}
