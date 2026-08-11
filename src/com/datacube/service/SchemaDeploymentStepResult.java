package com.datacube.service;

import java.util.Objects;

/** One rendered statement's actual terminal outcome, without retaining its SQL text. */
public record SchemaDeploymentStepResult(
        int index, String changeId, SchemaDeploymentState state) {
    public SchemaDeploymentStepResult {
        if (index < 1) throw new IllegalArgumentException("Deployment step index is invalid");
        changeId = Objects.requireNonNull(changeId, "changeId");
        state = Objects.requireNonNull(state, "state");
    }

    @Override
    public String toString() {
        return "SchemaDeploymentStepResult[index=" + index + ", state=" + state + "]";
    }
}
