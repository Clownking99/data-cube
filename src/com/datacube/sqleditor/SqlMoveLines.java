package com.datacube.sqleditor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Rotates line bodies in LF-normalized editor offsets without replacing any separator. */
public final class SqlMoveLines {
    public static final int MAX_TEXT_LENGTH = 8 * 1024 * 1024;
    public static final int MAX_LINES = 10_000;
    public enum Direction { UP, DOWN }
    public record Edit(int start, int end, String text) { }
    public record Plan(List<Edit> edits, int anchor, int caret, int lines, boolean movable) {
        public Plan { edits = List.copyOf(edits); }
    }
    private record Line(int start, int end) { }

    private SqlMoveLines() { }

    public static Plan plan(String text, int anchor, int caret, Direction direction) {
        Objects.requireNonNull(text, "text"); Objects.requireNonNull(direction, "direction");
        if (text.length() > MAX_TEXT_LENGTH || text.indexOf('\r') >= 0
                || anchor < 0 || caret < 0 || anchor > text.length() || caret > text.length())
            throw new IllegalArgumentException("Invalid normalized text or selection");
        int low = Math.min(anchor, caret), high = Math.max(anchor, caret);
        int first = text.lastIndexOf('\n', low - 1) + 1;
        int lastPosition = high > low && text.charAt(high - 1) == '\n' ? high - 1 : high;
        int lastEnd = lineEnd(text, lastPosition);
        List<Line> lines = new ArrayList<>();
        for (int start = first; start <= lastEnd;) {
            int end = lineEnd(text, start);
            if (lines.size() == MAX_LINES) throw new IllegalArgumentException("Too many selected lines");
            lines.add(new Line(start, end));
            if (end == lastEnd) break;
            start = end + 1;
        }
        int selectedLines = lines.size();
        boolean up = direction == Direction.UP;
        if (up ? first == 0 : lastEnd == text.length())
            return new Plan(List.of(), anchor, caret, selectedLines, false);
        int shift;
        if (up) {
            int previous = text.lastIndexOf('\n', first - 2) + 1;
            lines.addFirst(new Line(previous, first - 1));
            shift = previous - first;
        } else {
            int nextEnd = lineEnd(text, lastEnd + 1);
            lines.add(new Line(lastEnd + 1, nextEnd));
            shift = nextEnd - lastEnd;
        }
        List<Edit> edits = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Line target = lines.get(i), source = lines.get((i + (up ? 1 : lines.size() - 1)) % lines.size());
            String replacement = text.substring(source.start(), source.end());
            if (target.end() - target.start() != replacement.length()
                    || !text.regionMatches(target.start(), replacement, 0, replacement.length()))
                edits.add(new Edit(target.start(), target.end(), replacement));
        }
        // A range ending at the next line's start has no trailing separator after moving to EOF.
        return new Plan(edits, Math.min(text.length(), anchor + shift), Math.min(text.length(), caret + shift), selectedLines, true);
    }

    private static int lineEnd(String text, int start) {
        int end = text.indexOf('\n', start);
        return end < 0 ? text.length() : end;
    }
}
