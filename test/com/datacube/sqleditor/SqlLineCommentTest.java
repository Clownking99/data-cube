package com.datacube.sqleditor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlLineCommentTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void selectedIndentationIncludesMarkerInsteadOfFallingBackToAllSql(boolean backward) {
        String text = "  select 1;\nselect 2;";
        var plan = SqlLineComment.plan(text, backward ? 1 : 0, backward ? 0 : 1);
        String changed = apply(text, plan);
        var range = SqlExecutionRange.resolve(changed, plan.anchor(), plan.caret());
        assertTrue(range.selection()); assertEquals("  -- ", range.extract(changed));
        assertEquals("  -- select 1;\nselect 2;", changed);
        assertEquals(backward ? 5 : 0, plan.anchor()); assertEquals(backward ? 0 : 5, plan.caret());
    }

    @ParameterizedTest @CsvSource({"false,2", "true,2", "false,5", "true,5"})
    void selectedExecutionCannotBypassFirstInsertedCommentMarker(boolean backward, int low) {
        String text = "  select 1;\nselect 2;\nuntouched";
        int high = text.indexOf("untouched");
        var plan = SqlLineComment.plan(text, backward ? high : low, backward ? low : high);
        String changed = apply(text, plan);
        assertEquals("-- select 1;\n-- select 2;\n", SqlExecutionRange.resolve(changed, plan.anchor(), plan.caret()).extract(changed));
        assertEquals(backward ? high + 6 : 2, plan.anchor()); assertEquals(backward ? 2 : high + 6, plan.caret());
        assertTrue(changed.endsWith("\nuntouched"));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void selectedMixedSeparatorLinesPreserveDirectionAndExcludeNextLineStart(boolean backward) {
        String text = "  α\r\n\t中😀\nlast";
        int end = text.indexOf("last");
        var plan = SqlLineComment.plan(text, backward ? end : 2, backward ? 2 : end);
        String changed = apply(text, plan);
        assertEquals("  -- α\r\n\t-- 中😀\nlast", changed);
        assertEquals(2, plan.lines()); assertFalse(plan.uncomment());
        assertEquals(backward ? end + 6 : 2, plan.anchor());
        assertEquals(backward ? 2 : end + 6, plan.caret());
        var restored = SqlLineComment.plan(changed, plan.anchor(), plan.caret());
        assertTrue(restored.uncomment()); assertEquals(text, apply(changed, restored));
        assertEquals(backward ? end : 2, restored.anchor()); assertEquals(backward ? 2 : end, restored.caret());
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3, 4})
    void caretStaysCollapsedAndOnlyCurrentLineChanges(int caret) {
        var plan = SqlLineComment.plan("a\nb\n", caret, caret);
        assertEquals(caret < 2 ? "-- a\nb\n" : caret < 4 ? "a\n-- b\n" : "a\nb\n", apply("a\nb\n", plan));
        assertEquals(caret < 4 ? caret + 3 : caret, plan.caret());
        assertEquals(plan.caret(), plan.anchor()); assertEquals(1, plan.lines());
    }

    @Test void mixedCommentsAddOneLayerAndBlankLinesAndIndentationStayUntouched() {
        String text = " \tselect 1;\r\n\t-- prior\n \t\r\n\n\u3000text";
        var plan = SqlLineComment.plan(text, 0, text.length());
        assertEquals(" \t-- select 1;\r\n\t-- -- prior\n \t\r\n\n-- \u3000text", apply(text, plan));
        assertEquals(3, plan.edits().size()); assertEquals(5, plan.lines());
        assertEquals(0, plan.anchor(), "caret in indentation must not jump after inserted marker");
        String changed = apply(text, plan);
        assertEquals(text, apply(changed, SqlLineComment.plan(changed, 0, changed.length())));
    }

    @Test void removingOneMarkerRetainsTrailingWhitespaceAndClampsInsideRemovedMarker() {
        String text = "  -- one  \r\n\t--\ttwo\n--- three\r--";
        var plan = SqlLineComment.plan(text, text.length(), 3);
        assertEquals("  one  \r\n\t\ttwo\n- three\r", apply(text, plan));
        assertTrue(plan.uncomment()); assertEquals(2, plan.caret());
        assertEquals(text.length() - 9, plan.anchor());
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "\t\r\n \n\r"})
    void emptyAndWhitespaceOnlyRangesProduceNoEdit(String text) {
        var plan = SqlLineComment.plan(text, text.length(), 0);
        assertTrue(plan.edits().isEmpty()); assertEquals(text.length(), plan.anchor()); assertEquals(0, plan.caret());
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3})
    void caretWithinOrAfterCrLfHasUnambiguousPhysicalLine(int caret) {
        String text = "a\r\nb";
        var plan = SqlLineComment.plan(text, caret, caret);
        assertEquals(caret < 3 ? "-- a\r\nb" : "a\r\n-- b", apply(text, plan));
        assertEquals(caret + 3, plan.caret());
    }

    @ParameterizedTest @ValueSource(ints = {9999, 10000, 10001})
    void lineLimitRejectsWholeOperationButStillAllowsEditingOneLine(int count) {
        String text = "x\n".repeat(count);
        if (count <= 10000) assertEquals(count, SqlLineComment.plan(text, 0, text.length()).edits().size());
        else assertThrows(IllegalArgumentException.class, () -> SqlLineComment.plan(text, 0, text.length()));
        assertEquals(1, SqlLineComment.plan(text, 0, 0).edits().size());
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 1})
    void resultingTextLimitIsExact(int delta) {
        String text = "x".repeat(SqlLineComment.MAX_TEXT_LENGTH - 3 + delta);
        if (delta <= 0) assertEquals(1, SqlLineComment.plan(text, 0, 0).edits().size());
        else assertThrows(IllegalArgumentException.class, () -> SqlLineComment.plan(text, 0, 0));
    }

    @Test void inputLimitInvalidSelectionAndImmutableEditsAreEnforced() {
        String exact = "-- " + "x".repeat(SqlLineComment.MAX_TEXT_LENGTH - 3);
        assertTrue(SqlLineComment.plan(exact, 0, 0).uncomment());
        assertThrows(IllegalArgumentException.class, () -> SqlLineComment.plan(exact + "x", 0, 0));
        assertThrows(NullPointerException.class, () -> SqlLineComment.plan(null, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> SqlLineComment.plan("x", -1, 0));
        assertThrows(IllegalArgumentException.class, () -> SqlLineComment.plan("x", 0, -1));
        assertThrows(IllegalArgumentException.class, () -> SqlLineComment.plan("x", 2, 0));
        assertThrows(IllegalArgumentException.class, () -> SqlLineComment.plan("x", 0, 2));
        assertThrows(UnsupportedOperationException.class, () -> SqlLineComment.plan("x", 0, 0).edits().clear());
    }

    private static String apply(String text, SqlLineComment.Plan plan) {
        var changed = new StringBuilder(text);
        for (int i = plan.edits().size() - 1; i >= 0; i--) {
            var edit = plan.edits().get(i); changed.replace(edit.start(), edit.end(), edit.text());
        }
        return changed.toString();
    }
}
