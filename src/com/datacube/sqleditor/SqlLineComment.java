package com.datacube.sqleditor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Text-only, bounded line-prefix edits. Separators and SQL contents are never reconstructed. */
public final class SqlLineComment {
    public static final int MAX_TEXT_LENGTH = 8 * 1024 * 1024;
    public static final int MAX_LINES = 10_000;
    private SqlLineComment() { }

    public record Edit(int start, int end, String text) { }
    public record Plan(List<Edit> edits, int anchor, int caret, int lines, boolean uncomment) {
        public Plan { edits = List.copyOf(edits); }
    }

    public static Plan plan(String text, int anchor, int caret) {
        Objects.requireNonNull(text, "text");
        if (text.length() > MAX_TEXT_LENGTH || anchor < 0 || caret < 0
                || anchor > text.length() || caret > text.length())
            throw new IllegalArgumentException("Invalid text or selection size");
        int low = Math.min(anchor, caret), high = Math.max(anchor, caret), first = low;
        while (first > 0) {
            char previous = text.charAt(first - 1);
            if (previous == '\n' || (previous == '\r'
                    && !(first < text.length() && text.charAt(first) == '\n'))) break;
            first--;
        }
        List<Integer> prefixes = new ArrayList<>();
        boolean uncomment = true;
        int lines = 0;
        for (int start = first; start <= high;) {
            if (start == high && low != high && start != first) break;
            if (++lines > MAX_LINES) throw new IllegalArgumentException("Too many lines");
            int end = start;
            while (end < text.length() && text.charAt(end) != '\r' && text.charAt(end) != '\n') end++;
            int prefix = start;
            while (prefix < end && (text.charAt(prefix) == ' ' || text.charAt(prefix) == '\t')) prefix++;
            if (prefix < end) {
                prefixes.add(prefix);
                if (!text.startsWith("--", prefix)) uncomment = false;
            }
            if (end == text.length()) break;
            if (text.charAt(end++) == '\r' && end < text.length() && text.charAt(end) == '\n') end++;
            start = end;
        }
        List<Edit> edits = new ArrayList<>();
        int length = text.length();
        for (int prefix : prefixes) {
            if (uncomment) {
                int end = prefix + 2;
                if (end < text.length() && text.charAt(end) == ' ') end++;
                edits.add(new Edit(prefix, end, ""));
            } else {
                length += 3;
                if (length > MAX_TEXT_LENGTH) throw new IllegalArgumentException("Result too large");
                edits.add(new Edit(prefix, prefix, "-- "));
            }
        }
        int mappedLow = map(low, edits), mappedHigh = map(high, edits);
        if (low != high && !uncomment && !edits.isEmpty()) {
            // Selected execution must not bypass the first new marker or fall back to all SQL
            // when the original selection contained only indentation before that marker.
            mappedLow = Math.min(mappedLow, edits.getFirst().start());
            mappedHigh = Math.max(mappedHigh, edits.getFirst().start() + 3);
        }
        return new Plan(edits, anchor <= caret ? mappedLow : mappedHigh,
                anchor <= caret ? mappedHigh : mappedLow, lines, uncomment);
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
