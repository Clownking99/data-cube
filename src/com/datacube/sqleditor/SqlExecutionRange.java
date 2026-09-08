package com.datacube.sqleditor;

import java.util.Objects;

/** Immutable range for the existing "non-blank selection, otherwise all" execution rule. */
public record SqlExecutionRange(int start, int end, boolean selection) {
    public SqlExecutionRange {
        if (start < 0 || end < start) throw new IllegalArgumentException("Invalid SQL range");
    }

    public static SqlExecutionRange resolve(String text, int anchor, int caret) {
        Objects.requireNonNull(text, "text");
        int start = Math.min(anchor, caret);
        int end = Math.max(anchor, caret);
        if (start < 0 || end > text.length()) throw new IllegalArgumentException("Invalid SQL selection");
        // Deliberately preserve String.trim() semantics, including its treatment of Unicode spaces.
        // Scan the selection without allocating its text on each caret/selection notification.
        for (int i = start; i < end; i++) {
            if (text.charAt(i) > ' ') return new SqlExecutionRange(start, end, true);
        }
        return new SqlExecutionRange(0, text.length(), false);
    }

    /** Extract from the same text snapshot used to resolve this range. */
    public String extract(String text) {
        return text.substring(start, end);
    }
}
