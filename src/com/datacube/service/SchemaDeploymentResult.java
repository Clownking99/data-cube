package com.datacube.service;

import java.util.List;
import java.util.Objects;

/** Safe immutable summary for one deployment attempt. */
public record SchemaDeploymentResult(
        SchemaDeploymentState state,
        List<SchemaDeploymentStepResult> steps,
        String planDigest,
        List<String> safetyWarnings) {
    public SchemaDeploymentResult {
        state = Objects.requireNonNull(state, "state");
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        planDigest = planDigest == null ? "" : planDigest;
        safetyWarnings = List.copyOf(Objects.requireNonNull(safetyWarnings, "safetyWarnings"));
    }

    public SchemaDeploymentResult(
            SchemaDeploymentState state,
            List<SchemaDeploymentStepResult> steps,
            String planDigest) {
        this(state, steps, planDigest, List.of());
    }

    public boolean successful() {
        return state == SchemaDeploymentState.SUCCEEDED;
    }

    @Override
    public String toString() {
        return "SchemaDeploymentResult[state=" + state + ", stepCount=" + steps.size() + "]";
    }
}
