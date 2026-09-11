package com.datacube.sqleditor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlLineIndentTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void selectedLinesExcludeEndpointAtNextLineStartAndPreserveDirection(boolean backward) {
        String text = "α\r\n中😀\nlast";
        var plan = SqlLineIndent.plan(text, backward ? 7 : 1, backward ? 1 : 7, false);
        assertEquals("    α\r\n    中😀\nlast", apply(text, plan));
        assertEquals(2, plan.lines());
        assertEquals(backward ? 15 : 5, plan.anchor());
        assertEquals(backward ? 5 : 15, plan.caret());
        var reversed = SqlLineIndent.plan(apply(text, plan), plan.anchor(), plan.caret(), true);
        assertEquals(text, apply(apply(text, plan), reversed));
        assertEquals(backward ? 7 : 1, reversed.anchor());
        assertEquals(backward ? 1 : 7, reversed.caret());
    }

    @ParameterizedTest @CsvSource({"0,4", "1,5", "2,6", "3,7", "4,8"})
    void collapsedCaretOnlyIndentsItsLogicalLine(int caret, int expected) {
        String text = "a\nb\n";
        var plan = SqlLineIndent.plan(text, caret, caret, false);
        String actual = apply(text, plan);
        assertEquals(caret < 2 ? "    a\nb\n" : caret < 4 ? "a\n    b\n" : "a\nb\n    ", actual);
        assertEquals(1, plan.edits().size());
        assertEquals(expected, plan.anchor());
        assertEquals(expected, plan.caret());
    }

    @Test void emptyAndBlankLinesCanBeIndentedButWholeSelectionExcludesTrailingEmptyLine() {
        var empty = SqlLineIndent.plan("", 0, 0, false);
        assertEquals("    ", apply("", empty));
        assertEquals(4, empty.caret());
        String text = "\n\r\n\r";
        var plan = SqlLineIndent.plan(text, 0, text.length(), false);
        assertEquals("    \n    \r\n    \r", apply(text, plan));
        assertEquals(3, plan.edits().size());
    }

    @Test void outdentRemovesOnlyOneTabOrUpToFourSpacesAndClampsInsideRemovedPrefix() {
        String text = "\t  a\r\n      b\n c\r\u00a0keep\n\t\tend";
        var plan = SqlLineIndent.plan(text, text.length(), 1, true);
        assertEquals("  a\r\n  b\nc\r\u00a0keep\n\tend", apply(text, plan));
        assertEquals(text.length() - 7, plan.anchor());
        assertEquals(0, plan.caret());
        assertEquals(4, plan.edits().size());
        var inside = SqlLineIndent.plan("    x", 3, 1, true);
        assertEquals(0, inside.anchor()); assertEquals(0, inside.caret());
        assertEquals("x", apply("    x", inside));
    }

    @Test void unchangedOutdentRetainsSelectionAndProducesNoEdits() {
        var plan = SqlLineIndent.plan("select\n\u3000keep", 8, 2, true);
        assertTrue(plan.edits().isEmpty());
        assertEquals(8, plan.anchor()); assertEquals(2, plan.caret());
    }

    @Test void lineAndTextLimitsAreAllOrNothingAndExactBoundaryIsAccepted() {
        String atLines = "x\n".repeat(SqlLineIndent.MAX_LINES);
        assertEquals(SqlLineIndent.MAX_LINES, SqlLineIndent.plan(atLines, 0, atLines.length(), false).edits().size());
        String tooMany = atLines + "x";
        assertThrows(IllegalArgumentException.class, () -> SqlLineIndent.plan(tooMany, 0, tooMany.length(), false));
        assertEquals(1, SqlLineIndent.plan(tooMany, tooMany.length(), tooMany.length(), false).lines());
        String atText = "x".repeat(SqlLineIndent.MAX_TEXT_LENGTH - 4);
        assertEquals(1, SqlLineIndent.plan(atText, 0, 0, false).edits().size());
        assertThrows(IllegalArgumentException.class, () -> SqlLineIndent.plan(atText + "x", 0, 0, false));
        String over = atText + "xxxxx";
        assertThrows(IllegalArgumentException.class, () -> SqlLineIndent.plan(over, 0, 0, true));
        assertThrows(IllegalArgumentException.class, () -> SqlLineIndent.plan("x", -1, 0, false));
        assertThrows(IllegalArgumentException.class, () -> SqlLineIndent.plan("x", 0, 2, false));
    }

    private static String apply(String text, SqlLineIndent.Plan plan) {
        StringBuilder result = new StringBuilder(text);
        for (int i = plan.edits().size() - 1; i >= 0; i--) {
            var edit = plan.edits().get(i);
            result.replace(edit.start(), edit.end(), edit.text());
        }
        return result.toString();
    }
}
