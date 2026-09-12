package com.datacube.sqleditor;

import java.util.Objects;

/** One bounded insertion in CodeArea's LF-normalized text; never interprets SQL. */
public final class SqlDuplicateLines {
    public static final int MAX_TEXT_LENGTH = 8 * 1024 * 1024;
    public static final int MAX_LINES = 10_000;

    private SqlDuplicateLines() { }

    public record Plan(int position, String insertion, int anchor, int caret, int lines) { }

    public static Plan plan(String text, int anchor, int caret) {
        Objects.requireNonNull(text, "text");
        if (text.length() > MAX_TEXT_LENGTH || anchor < 0 || caret < 0
                || anchor > text.length() || caret > text.length() || text.indexOf('\r') >= 0)
            throw new IllegalArgumentException("Invalid normalized text or selection size");
        int low = Math.min(anchor, caret), high = Math.max(anchor, caret);
        int first = text.lastIndexOf('\n', low - 1) + 1;
        int lastPosition = high > low && text.charAt(high - 1) == '\n' ? high - 1 : high;
        int end = text.indexOf('\n', lastPosition);
        boolean terminated = end >= 0;
        if (!terminated) end = text.length();
        int lines = 1;
        for (int i = first; i < end; i++)
            if (text.charAt(i) == '\n' && ++lines > MAX_LINES)
                throw new IllegalArgumentException("Too many lines");
        int position = terminated ? end + 1 : end;
        int added = terminated ? position - first : position - first + 1;
        if ((long) text.length() + added > MAX_TEXT_LENGTH)
            throw new IllegalArgumentException("Result too large");
        String insertion = terminated ? text.substring(first, position) : "\n" + text.substring(first);
        int shift = position - first + (terminated ? 0 : 1);
        return new Plan(position, insertion, anchor + shift, caret + shift, lines);
    }
}
