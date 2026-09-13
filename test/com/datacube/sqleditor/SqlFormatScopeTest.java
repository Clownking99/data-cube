package com.datacube.sqleditor;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlFormatScopeTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void selectedRangePreservesOuterTextBoundaryWhitespaceAndDirection(boolean reverse) {
        String prefix = "-- outside\r\n", selected = " \tselect a,b from t;\r\n", suffix = "select untouched;";
        String text = prefix + selected + suffix; int end = prefix.length() + selected.length();
        var plan = SqlFormatScope.plan(text, reverse ? end : prefix.length(), reverse ? prefix.length() : end);
        String expected = prefix + " \tSELECT a,\n       b\n  FROM t;\r\n" + suffix;
        assertEquals(expected, apply(text, plan)); assertTrue(plan.changed()); assertTrue(plan.selection()); assertFalse(plan.blank());
        int newEnd = expected.length() - suffix.length();
        assertEquals(reverse ? newEnd : prefix.length(), plan.anchor());
        assertEquals(reverse ? prefix.length() : newEnd, plan.caret());
        assertEquals(prefix.length() + 2, plan.start()); assertEquals(end - 2, plan.end());
    }

    @Test void selectionDoesNotExpandToStatementOrWordBoundaries() {
        String text = "select a from t; select b from u;";
        var plan = SqlFormatScope.plan(text, 7, 8, token -> { assertEquals("a", token); return "b"; });
        assertEquals("select b from t; select b from u;", apply(text, plan));
        assertEquals(7, plan.anchor()); assertEquals(8, plan.caret());
    }

    @ParameterizedTest @ValueSource(strings = {"", " \t\n", "select 1;\n  \nselect 2;"})
    void blankRangeNeverInvokesFormatterOrFallsBackToWholeText(String text) {
        int start = text.startsWith("select") ? text.indexOf("  ") : 0;
        int end = text.startsWith("select") ? start + 2 : text.length();
        var plan = SqlFormatScope.plan(text, start, end, token -> { fail("blank must not invoke formatter"); return token; });
        assertFalse(plan.changed()); assertTrue(plan.blank()); assertEquals(text, apply(text, plan));
        assertEquals(start, plan.anchor()); assertEquals(end, plan.caret());
    }

    @Test void wholeTextKeepsBoundaryWhitespaceAndClampsCollapsedCaret() {
        var plan = SqlFormatScope.plan("  select 1;  \n", 14, 14, token -> "x");
        assertEquals("  x  \n", apply("  select 1;  \n", plan));
        assertFalse(plan.selection()); assertEquals(6, plan.anchor()); assertEquals(6, plan.caret());
    }

    @Test void alreadyFormattedRangeKeepsCaretAndDoesNotPlanAnEdit() {
        String sql = "SELECT a,\n       b\n  FROM t;";
        var plan = SqlFormatScope.plan(sql, sql.length(), 0);
        assertFalse(plan.changed()); assertFalse(plan.blank()); assertEquals(sql.length(), plan.anchor()); assertEquals(0, plan.caret());
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 1})
    void scopeLimitIsCheckedBeforeFormatter(int delta) {
        String text = "x".repeat(SqlFormatScope.MAX_SCOPE_LENGTH + delta); var calls = new AtomicInteger();
        if (delta > 0) {
            assertThrows(IllegalArgumentException.class, () -> SqlFormatScope.plan(text, 0, 0, token -> { calls.incrementAndGet(); return token; }));
            assertEquals(0, calls.get());
        } else {
            var plan = SqlFormatScope.plan(text, 0, 0, token -> { calls.incrementAndGet(); return token; });
            assertFalse(plan.changed()); assertEquals(1, calls.get());
        }
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 1})
    void wholeInputLimitStillAllowsSmallSelectionAtBoundary(int delta) {
        String text = "x".repeat(SqlFormatScope.MAX_TEXT_LENGTH + delta);
        if (delta > 0) assertThrows(IllegalArgumentException.class, () -> SqlFormatScope.plan(text, 0, 1, token -> "y"));
        else {
            var plan = SqlFormatScope.plan(text, 0, 1, token -> "y");
            assertTrue(plan.changed()); assertEquals(0, plan.start()); assertEquals(1, plan.end());
            assertEquals("y", plan.replacement());
        }
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 1})
    void resultLimitAcceptsBoundaryAndRejectsOversizeBeforeApplying(int delta) {
        String replacement = "x".repeat(SqlFormatScope.MAX_TEXT_LENGTH + delta - 2);
        if (delta > 0) assertThrows(IllegalArgumentException.class, () -> SqlFormatScope.plan("[a]", 1, 2, token -> replacement));
        else {
            var plan = SqlFormatScope.plan("[a]", 1, 2, token -> replacement);
            assertEquals(SqlFormatScope.MAX_TEXT_LENGTH + delta - 1, plan.caret());
            assertEquals(1, plan.anchor()); assertEquals(replacement, plan.replacement());
        }
    }

    @ParameterizedTest @CsvSource({"-1,0", "0,-1", "4,0", "0,4"})
    void invalidPositionsAreRejected(int anchor, int caret) {
        assertThrows(IllegalArgumentException.class, () -> SqlFormatScope.plan("abc", anchor, caret));
    }

    private static String apply(String text, SqlFormatScope.Plan plan) {
        return text.substring(0, plan.start()) + plan.replacement() + text.substring(plan.end());
    }
}
