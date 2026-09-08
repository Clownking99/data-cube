package com.datacube.sqleditor;

import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Builds a bounded literal replacement candidate; never writes files or edits a live document. */
public final class SqlTextReplacement {
    public static final int MAX_REPLACEMENT_LENGTH = 4096;
    public static final int MAX_TEXT_LENGTH = 8 * 1024 * 1024;
    private SqlTextReplacement() { }

    public record Edit(String text, int replacements) { }

    public static Edit replace(String text, SqlTextSearch.Result result, String replacement) {
        Objects.requireNonNull(text);
        Objects.requireNonNull(result);
        Objects.requireNonNull(replacement);
        checkCancelled();
        if (result.truncated()) throw new IllegalArgumentException("Incomplete match set");
        if (replacement.length() > MAX_REPLACEMENT_LENGTH)
            throw new IllegalArgumentException("Replacement is too long");
        long length = text.length();
        int end = 0;
        int changes = 0;
        for (var match : result.matches()) {
            checkCancelled();
            if (match.start() < end || match.end() <= match.start() || match.end() > text.length())
                throw new IllegalArgumentException("Invalid match range");
            length += (long) replacement.length() - (match.end() - match.start());
            if (!text.substring(match.start(), match.end()).equals(replacement)) changes++;
            end = match.end();
        }
        if (length > MAX_TEXT_LENGTH) throw new IllegalArgumentException("Replacement result is too large");
        if (changes == 0) return new Edit(text, 0);
        var output = new StringBuilder((int) length);
        end = 0;
        for (var match : result.matches()) {
            checkCancelled();
            output.append(text, end, match.start()).append(replacement);
            end = match.end();
        }
        output.append(text, end, text.length());
        checkCancelled();
        return new Edit(output.toString(), changes);
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Replacement cancelled");
    }
}
