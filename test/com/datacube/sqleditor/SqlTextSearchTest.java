package com.datacube.sqleditor;

import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class SqlTextSearchTest {
    @Test void searchesLiteralSymbolsWithoutTreatingThemAsRegex() {
        assertEquals(List.of(new SqlTextSearch.Match(2, 7), new SqlTextSearch.Match(10, 15)),
                SqlTextSearch.find("x [a].* y [a].*", "[a].*", true).matches());
    }

    @Test void unicodeCaseFoldingKeepsCodeAreaUtf16Offsets() {
        assertEquals(List.of(new SqlTextSearch.Match(3, 6), new SqlTextSearch.Match(7, 10)),
                SqlTextSearch.find("😀 Äbc äBC", "äbc", false).matches());
        assertEquals(List.of(new SqlTextSearch.Match(3, 6)),
                SqlTextSearch.find("😀 Äbc äBC", "Äbc", true).matches());
    }

    @Test void emptyAndMissingQueriesHaveNoMatchesAndCannotNavigate() {
        for (var result : List.of(SqlTextSearch.find("abc", "", true),
                SqlTextSearch.find("", "x", false), SqlTextSearch.find("abc", "z", false))) {
            assertTrue(result.matches().isEmpty());
            assertFalse(result.truncated());
            assertEquals(-1, result.indexFrom(0, true));
            assertEquals(-1, result.indexFrom(5, false));
        }
    }

    @Test void nonOverlappingMatchesNavigateRelativeToSelectionAndWrap() {
        var result = SqlTextSearch.find("aaaa aa", "aa", true);
        assertEquals(List.of(new SqlTextSearch.Match(0, 2), new SqlTextSearch.Match(2, 4),
                new SqlTextSearch.Match(5, 7)), result.matches());
        assertEquals(0, result.indexFrom(0, true));
        assertEquals(1, result.indexFrom(2, true));
        assertEquals(2, result.indexFrom(4, true));
        assertEquals(0, result.indexFrom(7, true));
        assertEquals(1, result.indexFrom(5, false));
        assertEquals(2, result.indexFrom(0, false));
        assertThrows(UnsupportedOperationException.class, () -> result.matches().clear());
    }

    @ParameterizedTest @ValueSource(ints = {9999, 10000, 10001})
    void matchLimitDistinguishesExactCountFromTruncation(int count) {
        var result = SqlTextSearch.find("x ".repeat(count), "x", true);
        assertEquals(Math.min(count, 10000), result.matches().size());
        assertEquals(count > 10000, result.truncated());
    }

    @Test void boundsQueryLengthWithoutTruncatingIt() {
        String allowed = "x".repeat(1024);
        assertEquals(List.of(new SqlTextSearch.Match(0, 1024)),
                SqlTextSearch.find(allowed, allowed, true).matches());
        assertThrows(IllegalArgumentException.class,
                () -> SqlTextSearch.find(allowed, allowed + "x", false));
    }

    @Test void interruptedSearchDoesNotPublishPartialResults() {
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> SqlTextSearch.find("x ".repeat(10000), "x", true));
        } finally { Thread.interrupted(); }
    }
}
