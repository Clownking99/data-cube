package com.datacube.sqleditor.result;

import java.util.Objects;

/** Bounded table presentation only; never use this text for copying, export or cell inspection. */
public final class CompactResultText {
    public static final int MAX_PREFIX_LENGTH = 256;

    private CompactResultText() { }

    public static String preview(String text) {
        Objects.requireNonNull(text);
        int end = Math.min(text.length(), MAX_PREFIX_LENGTH);
        boolean lineBreak = false;
        // Include the boundary character so a break immediately after a full prefix remains explicit.
        for (int i = 0; i <= end && i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n' || c == '\u0085' || c == '\u2028' || c == '\u2029'
                    || c == '\u000b' || c == '\f') {
                end = i;
                lineBreak = true;
                break;
            }
        }
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) end--;
        return text.substring(0, end) + (lineBreak ? " ↵ …" : end < text.length() ? " …" : "");
    }
}
