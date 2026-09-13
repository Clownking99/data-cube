package com.datacube.sqleditor;

import java.util.Objects;
import java.util.function.UnaryOperator;

/** A bounded text replacement; does not infer SQL statements or validate dialect semantics. */
public final class SqlFormatScope {
    public static final int MAX_SCOPE_LENGTH = 256 * 1024;
    public static final int MAX_TEXT_LENGTH = 8 * 1024 * 1024;

    private SqlFormatScope() { }

    public record Plan(int start, int end, String replacement, int anchor, int caret,
                       boolean selection, boolean blank, boolean changed) { }

    public static Plan plan(String text, int anchor, int caret) {
        return plan(text, anchor, caret, SqlFormatter::format);
    }

    static Plan plan(String text, int anchor, int caret, UnaryOperator<String> formatter) {
        Objects.requireNonNull(text, "text"); Objects.requireNonNull(formatter, "formatter");
        if (anchor < 0 || caret < 0 || anchor > text.length() || caret > text.length())
            throw new IllegalArgumentException("Invalid selection");
        boolean selection = anchor != caret;
        int rangeStart = selection ? Math.min(anchor, caret) : 0;
        int rangeEnd = selection ? Math.max(anchor, caret) : text.length();
        if (text.length() > MAX_TEXT_LENGTH || rangeEnd - rangeStart > MAX_SCOPE_LENGTH)
            throw new IllegalArgumentException("Formatting input limit exceeded");
        int start = rangeStart, end = rangeEnd;
        // Do not remove a line separator or space joining the selection to adjacent text.
        while (start < end && Character.isWhitespace(text.charAt(start))) start++;
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
        if (start == end) return new Plan(start, end, "", anchor, caret, selection, true, false);
        String before = text.substring(start, end);
        String replacement = Objects.requireNonNull(formatter.apply(before), "formatted text");
        long resultLength = (long) text.length() - (end - start) + replacement.length();
        if (resultLength > MAX_TEXT_LENGTH) throw new IllegalArgumentException("Formatting result limit exceeded");
        boolean changed = !before.equals(replacement);
        if (!changed) return new Plan(start, end, replacement, anchor, caret, selection, false, false);
        int delta = replacement.length() - (end - start);
        int nextAnchor = selection ? (anchor > caret ? rangeEnd + delta : rangeStart)
                : Math.min(caret, (int) resultLength);
        int nextCaret = selection ? (anchor > caret ? rangeStart : rangeEnd + delta) : nextAnchor;
        return new Plan(start, end, replacement, nextAnchor, nextCaret, selection, false, true);
    }
}
