package com.datacube.sqleditor;

import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlWholeWordSearchTest {
    @Test void independentIdentifiersKeepUtf16OffsetsAndCaseOption() {
        String sql = "id user_id id2 ID t.id";
        assertEquals(List.of(new SqlTextSearch.Match(0, 2), new SqlTextSearch.Match(15, 17),
                new SqlTextSearch.Match(20, 22)), SqlTextSearch.find(sql, "id", false, true).matches());
        assertEquals(List.of(new SqlTextSearch.Match(0, 2), new SqlTextSearch.Match(20, 22)),
                SqlTextSearch.find(sql, "id", true, true).matches());
        assertEquals(SqlTextSearch.find(sql, "id", false), SqlTextSearch.find(sql, "id", false, false));
        assertEquals(5, SqlTextSearch.find(sql, "id", false).matches().size());
    }

    @ParameterizedTest @ValueSource(strings = {"a", "2", "_", "$", "#", "中", "é", "\u0301", "\u203F", "𐐀", "\u200D"})
    void identifierNeighborsOnEitherSideAreNotBoundaries(String neighbor) {
        var result = SqlTextSearch.find(neighbor + "id id" + neighbor + " id", "id", true, true);
        int start = neighbor.length() * 2 + 6;
        assertEquals(List.of(new SqlTextSearch.Match(start, start + 2)), result.matches());
        assertFalse(result.truncated());
    }

    @Test void punctuationAndQuotesAreBoundariesButCommentsAndStringsAreNotExcluded() {
        String text = "id.id,'id',\"id\",(id)--id\nid";
        var result = SqlTextSearch.find(text, "id", true, true);
        assertEquals(7, result.matches().size());
        assertEquals("key.key,'key',\"key\",(key)--key\nkey", SqlTextReplacement.replace(text, result, "key").text());
    }

    @Test void supplementaryCharactersAndCombiningMarksKeepOffsetsAndAreNotSplit() {
        String text = "😀 id 𐐀id id𐐀 id\u0301";
        assertEquals(List.of(new SqlTextSearch.Match(3, 5)), SqlTextSearch.find(text, "id", false, true).matches());
        assertEquals(List.of(new SqlTextSearch.Match(0, 2)), SqlTextSearch.find("𐐀 𐐀id", "𐐨", false, true).matches());
        assertEquals(List.of(new SqlTextSearch.Match(0, 2)), SqlTextSearch.find("e\u0301 e\u0301x", "e\u0301", true, true).matches());
        assertTrue(SqlTextSearch.find("😀", "\uD83D", true, true).matches().isEmpty());
        assertTrue(SqlTextSearch.find("😀", "\uDE00", true, true).matches().isEmpty());
    }

    @Test void rejectedOverlappingPhraseDoesNotHideLaterWholeMatch() {
        assertEquals(List.of(new SqlTextSearch.Match(3, 6)),
                SqlTextSearch.find("xa a a", "a a", true, true).matches());
        assertEquals(List.of(new SqlTextSearch.Match(0, 3)),
                SqlTextSearch.find("a a a", "a a", true, true).matches(), "accepted matches remain non-overlapping");
    }

    @Test void phraseAndSymbolsStayLiteralAndWhitespaceIsNotTrimmed() {
        assertEquals(List.of(new SqlTextSearch.Match(0, 5)),
                SqlTextSearch.find("[a].* x[a].*", "[a].*", false, true).matches());
        assertEquals(List.of(new SqlTextSearch.Match(0, 4)),
                SqlTextSearch.find(" id ", " id ", true, true).matches());
        assertTrue(SqlTextSearch.find("id", " id ", true, true).matches().isEmpty());
    }

    @ParameterizedTest @ValueSource(ints = {9999, 10000, 10001})
    void onlyAcceptedWholeMatchesConsumeTheLimit(int count) {
        String rejected = "user_id ".repeat(10002);
        var result = SqlTextSearch.find(rejected + "id ".repeat(count), "id", true, true);
        assertEquals(Math.min(count, 10000), result.matches().size());
        assertEquals(count > 10000, result.truncated());
        assertEquals(rejected.length(), result.matches().getFirst().start());
        assertEquals(rejected.length() + 3 * (Math.min(count, 10000) - 1) + 2, result.matches().getLast().end());
    }

    @Test void queryBoundsEmptyAndCancellationStillApply() {
        assertEquals(List.of(), SqlTextSearch.find("id", "", false, true).matches());
        assertEquals(List.of(), SqlTextSearch.find("", "id", false, true).matches());
        String query = "x".repeat(1024);
        assertEquals(List.of(new SqlTextSearch.Match(0, 1024)), SqlTextSearch.find(query, query, true, true).matches());
        assertThrows(IllegalArgumentException.class, () -> SqlTextSearch.find(query, query + "x", true, true));
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> SqlTextSearch.find("user_id ".repeat(10001), "id", true, true));
        } finally { Thread.interrupted(); }
    }
}
