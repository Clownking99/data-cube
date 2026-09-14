package com.datacube.sqleditor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;

/** Bounded literal matching; all offsets are UTF-16 offsets understood by CodeArea. */
public final class SqlTextSearch {
    public static final int MAX_QUERY_LENGTH = 1024;
    public static final int MAX_MATCHES = 10000;
    private SqlTextSearch() { }

    public record Match(int start, int end) { }
    public record Result(List<Match> matches, boolean truncated) {
        public Result { matches = List.copyOf(matches); }

        public int indexFrom(int boundary, boolean forward) {
            if (matches.isEmpty()) return -1;
            if (forward) {
                for (int i = 0; i < matches.size(); i++) if (matches.get(i).start() >= boundary) return i;
                return 0;
            }
            for (int i = matches.size() - 1; i >= 0; i--) if (matches.get(i).start() < boundary) return i;
            return matches.size() - 1;
        }
    }

    public static Result find(String text, String query, boolean matchCase) {
        return find(text, query, matchCase, false);
    }

    /** Whole words use Unicode identifier-part boundaries plus SQL's dollar/hash characters, not SQL parsing. */
    public static Result find(String text, String query, boolean matchCase, boolean wholeWord) {
        Objects.requireNonNull(text);
        Objects.requireNonNull(query);
        checkCancelled();
        if (query.length() > MAX_QUERY_LENGTH) throw new IllegalArgumentException("Search query is too long");
        if (query.isEmpty()) return new Result(List.of(), false);
        int flags = Pattern.LITERAL | (matchCase ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        var matcher = Pattern.compile(query, flags).matcher(new InterruptibleText(text));
        List<Match> matches = new ArrayList<>();
        int from = 0;
        while (matcher.find(from)) {
            checkCancelled();
            int start = matcher.start(), end = matcher.end();
            if (wholeWord && !wholeWordBoundary(text, start, end)) {
                // An invalid candidate may overlap a later valid phrase; do not skip its entire length.
                from = start + Character.charCount(text.codePointAt(start));
                continue;
            }
            if (matches.size() == MAX_MATCHES) return new Result(matches, true);
            matches.add(new Match(start, end));
            from = end;
        }
        checkCancelled();
        return new Result(matches, false);
    }

    private static boolean wholeWordBoundary(String text, int start, int end) {
        return !splitsCodePoint(text, start) && !splitsCodePoint(text, end)
                && (start == 0 || !identifierPart(text.codePointBefore(start)))
                && (end == text.length() || !identifierPart(text.codePointAt(end)));
    }

    private static boolean identifierPart(int codePoint) {
        return Character.isUnicodeIdentifierPart(codePoint) || codePoint == '$' || codePoint == '#';
    }

    private static boolean splitsCodePoint(String text, int offset) {
        return offset > 0 && offset < text.length() && Character.isHighSurrogate(text.charAt(offset - 1))
                && Character.isLowSurrogate(text.charAt(offset));
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Search cancelled");
    }

    // Also allow interruption during a long scan that produces no matches.
    private static final class InterruptibleText implements CharSequence {
        private final String text;
        private int reads;
        private InterruptibleText(String text) { this.text = text; }
        @Override public int length() { return text.length(); }
        @Override public char charAt(int index) {
            if ((++reads & 1023) == 0) checkCancelled();
            return text.charAt(index);
        }
        @Override public CharSequence subSequence(int start, int end) {
            return new InterruptibleText(text.substring(start, end));
        }
        @Override public String toString() { return text; }
    }
}
