package com.datacube.sqleditor;

import java.nio.file.Path;
import java.util.Objects;

/** Pure editor state for one SQL script's identity and saved text baseline. */
public final class SqlScriptDocument {
    private Path path;
    private SqlScriptFileStore.Target target;
    private String baseline;

    public SqlScriptDocument() {
        this("");
    }

    public SqlScriptDocument(String baseline) {
        this.baseline = Objects.requireNonNull(baseline, "baseline");
    }

    public Path path() {
        return path;
    }

    public SqlScriptFileStore.Target target() {
        return target;
    }

    public boolean dirty(String currentText) {
        return !baseline.equals(Objects.requireNonNull(currentText, "currentText"));
    }

    public String title(String fallback, String currentText) {
        String baseTitle = path == null ? Objects.requireNonNull(fallback, "fallback")
                : path.getFileName().toString();
        return dirty(currentText) ? baseTitle + "*" : baseTitle;
    }

    public void attach(SqlScriptFileStore.Loaded loaded) {
        bind(loaded);
    }

    public void saved(SqlScriptFileStore.Loaded loaded) {
        bind(loaded);
    }

    private void bind(SqlScriptFileStore.Loaded loaded) {
        SqlScriptFileStore.Loaded snapshot = Objects.requireNonNull(loaded, "loaded");
        path = Objects.requireNonNull(snapshot.path(), "loaded.path");
        target = Objects.requireNonNull(snapshot.target(), "loaded.target");
        baseline = Objects.requireNonNull(snapshot.text(), "loaded.text");
    }
}
