package com.datacube.schemadiff;

import com.datacube.spi.schemadiff.SchemaSnapshot;

import java.util.List;
import java.util.Objects;

public record SchemaDiffResult(
        SchemaSnapshot source, SchemaSnapshot target,
        List<SchemaDifference> differences,
        List<RenameSuggestion> renameSuggestions) {
    public SchemaDiffResult {
        source = Objects.requireNonNull(source, "source");
        target = Objects.requireNonNull(target, "target");
        differences = List.copyOf(Objects.requireNonNull(differences, "differences"));
        renameSuggestions = List.copyOf(Objects.requireNonNull(renameSuggestions, "renameSuggestions"));
    }

    @Override
    public String toString() {
        return "SchemaDiffResult[differenceCount=" + differences.size()
                + ", renameSuggestionCount=" + renameSuggestions.size() + "]";
    }
}
