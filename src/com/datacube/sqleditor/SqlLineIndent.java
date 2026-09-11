package com.datacube.sqleditor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded line-prefix edits in original UTF-16 offsets; never rewrites separators. */
public final class SqlLineIndent {
    public static final int MAX_TEXT_LENGTH = 8 * 1024 * 1024;
    public static final int MAX_LINES = 10_000;
    private static final String INDENT = "    ";

    private SqlLineIndent() { }

    public record Edit(int start, int end, String text) { }
    public record Plan(List<Edit> edits, int anchor, int caret, int lines) {
        public Plan { edits = List.copyOf(edits); }
    }

    public static Plan plan(String text, int anchor, int caret, boolean outdent) {
        Objects.requireNonNull(text, "text");
        if (text.length() > MAX_TEXT_LENGTH || anchor < 0 || caret < 0
                || anchor > text.length() || caret > text.length())
            throw new IllegalArgumentException("Invalid text or selection size");
        int low = Math.min(anchor, caret), high = Math.max(anchor, caret);
        int first = low;
        while (first > 0) {
            char previous = text.charAt(first - 1);
            if (previous == '\n' || (previous == '\r'
                    && !(first < text.length() && text.charAt(first) == '\n'))) break;
            first--;
        }
        List<Edit> edits = new ArrayList<>();
        int lines = 0, length = text.length();
        for (int start = first; start <= high;) {
            if (start == high && low != high && start != first) break;
            if (++lines > MAX_LINES) throw new IllegalArgumentException("Too many lines");
            if (outdent) {
                int end = start;
                if (end < text.length() && text.charAt(end) == '\t') end++;
                else while (end < text.length() && end - start < INDENT.length()
                        && text.charAt(end) == ' ') end++;
                if (end > start) {
                    edits.add(new Edit(start, end, ""));
                    length -= end - start;
                }
            } else {
                length += INDENT.length();
                if (length > MAX_TEXT_LENGTH) throw new IllegalArgumentException("Result too large");
                edits.add(new Edit(start, start, INDENT));
            }
            int next = start;
            while (next < text.length() && text.charAt(next) != '\n' && text.charAt(next) != '\r') next++;
            if (next == text.length()) break;
            if (text.charAt(next++) == '\r' && next < text.length() && text.charAt(next) == '\n') next++;
            start = next;
        }
        return new Plan(edits, map(anchor, edits), map(caret, edits), lines);
    }

    private static int map(int position, List<Edit> edits) {
        int shift = 0;
        for (Edit edit : edits) {
            if (edit.start() > position) break;
            if (position < edit.end()) return edit.start() + shift;
            shift += edit.text().length() - (edit.end() - edit.start());
        }
        return position + shift;
    }
}
