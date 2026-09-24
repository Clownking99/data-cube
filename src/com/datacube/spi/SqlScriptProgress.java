package com.datacube.spi;

import com.datacube.spi.model.ScriptOutcome;
import java.util.List;

/** Detached, immutable completed occurrences. No connection or executable callback escapes. */
public record SqlScriptProgress(List<ScriptOutcome> outcomes, int completed, int total, long elapsedMillis) {
    public SqlScriptProgress { outcomes = List.copyOf(outcomes); }
}
