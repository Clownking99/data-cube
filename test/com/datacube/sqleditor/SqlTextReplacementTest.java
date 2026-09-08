package com.datacube.sqleditor;

import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqlTextReplacementTest {
    @Test void literalUnicodeAndCaseInsensitiveMatchesPreserveUnmatchedText() {
        String text = "select '😀中'; SELECT $a; selectz;";
        var edit = SqlTextReplacement.replace(text, SqlTextSearch.find(text, "select", false), "$1\\中");
        assertEquals("$1\\中 '😀中'; $1\\中 $a; $1\\中z;", edit.text());
        assertEquals(3, edit.replacements());
    }

    @Test void emptyReplacementDeletesNonOverlappingMatches() {
        var edit = SqlTextReplacement.replace("aaaaa", SqlTextSearch.find("aaaaa", "aa", true), "");
        assertEquals("a", edit.text());
        assertEquals(2, edit.replacements());
    }

    @Test void noMatchesAndIdenticalReplacementProduceNoChange() {
        String text = "a A";
        assertEquals(new SqlTextReplacement.Edit(text, 0),
                SqlTextReplacement.replace(text, SqlTextSearch.find(text, "missing", true), "b"));
        assertEquals(new SqlTextReplacement.Edit(text, 0),
                SqlTextReplacement.replace(text, SqlTextSearch.find(text, "a", true), "a"));
        assertEquals(new SqlTextReplacement.Edit("a a", 1),
                SqlTextReplacement.replace(text, SqlTextSearch.find(text, "a", false), "a"));
    }

    @Test void completeLimitIsAllowedButTruncatedSearchCannotPartiallyReplace() {
        String exact = "a".repeat(SqlTextSearch.MAX_MATCHES);
        assertEquals(new SqlTextReplacement.Edit("b".repeat(SqlTextSearch.MAX_MATCHES), SqlTextSearch.MAX_MATCHES),
                SqlTextReplacement.replace(exact, SqlTextSearch.find(exact, "a", true), "b"));
        String over = exact + "a";
        assertThrows(IllegalArgumentException.class,
                () -> SqlTextReplacement.replace(over, SqlTextSearch.find(over, "a", true), "b"));
    }

    @Test void replacementAndOutputLimitsRejectExpansionBeforeAllocation() {
        var result = SqlTextSearch.find("x", "x", true);
        String max = "a".repeat(SqlTextReplacement.MAX_REPLACEMENT_LENGTH);
        assertEquals(max, SqlTextReplacement.replace("x", result, max).text());
        assertThrows(IllegalArgumentException.class, () -> SqlTextReplacement.replace("x", result, max + "a"));
        String large = "x" + " ".repeat(SqlTextReplacement.MAX_TEXT_LENGTH - 1);
        assertEquals(SqlTextReplacement.MAX_TEXT_LENGTH, SqlTextReplacement.replace(large, result, "y").text().length());
        assertThrows(IllegalArgumentException.class, () -> SqlTextReplacement.replace(large, result, "yy"));
    }

    @Test void malformedRangesCannotCorruptTheCandidate() {
        for (var ranges : List.of(List.of(new SqlTextSearch.Match(-1, 1)),
                List.of(new SqlTextSearch.Match(0, 0)), List.of(new SqlTextSearch.Match(0, 4)),
                List.of(new SqlTextSearch.Match(0, 2), new SqlTextSearch.Match(1, 3)))) {
            assertThrows(IllegalArgumentException.class,
                    () -> SqlTextReplacement.replace("abc", new SqlTextSearch.Result(ranges, false), "x"));
        }
    }

    @Test void interruptedWorkCannotProduceAnEdit() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class,
                    () -> SqlTextReplacement.replace("abc", new SqlTextSearch.Result(List.of(), false), "x"));
        } finally { Thread.interrupted(); }
    }
}
