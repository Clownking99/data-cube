package com.datacube.sqleditor;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqlDuplicateLinesTest {
    static Stream<Arguments> copies() {
        return Stream.of(
                Arguments.of("first line", "abc\ndef", 1, 1, "abc\nabc\ndef", 5, 5, 1),
                Arguments.of("middle line", "a\nxyz\nz", 4, 4, "a\nxyz\nxyz\nz", 8, 8, 1),
                Arguments.of("unterminated last", "a\nxyz", 5, 5, "a\nxyz\nxyz", 9, 9, 1),
                Arguments.of("partial selection", "abc\ndef\ntail", 1, 6, "abc\ndef\nabc\ndef\ntail", 9, 14, 2),
                Arguments.of("backward selection", "abc\ndef\ntail", 6, 1, "abc\ndef\nabc\ndef\ntail", 14, 9, 2),
                Arguments.of("next line excluded", "abc\ndef", 0, 4, "abc\nabc\ndef", 4, 8, 1),
                Arguments.of("backward next line excluded", "abc\ndef", 4, 0, "abc\nabc\ndef", 8, 4, 1),
                Arguments.of("caret at line start", "abc\ndef", 4, 4, "abc\ndef\ndef", 8, 8, 1),
                Arguments.of("whole unterminated", "ab\ncd", 0, 5, "ab\ncd\nab\ncd", 6, 11, 2),
                Arguments.of("whole terminated", "ab\ncd\n", 0, 6, "ab\ncd\nab\ncd\n", 6, 12, 2),
                Arguments.of("empty", "", 0, 0, "\n", 1, 1, 1),
                Arguments.of("middle empty", "a\n\nb", 2, 2, "a\n\n\nb", 3, 3, 1),
                Arguments.of("trailing empty", "a\n", 2, 2, "a\n\n", 3, 3, 1),
                Arguments.of("only newline", "\n", 0, 1, "\n\n", 1, 2, 1),
                Arguments.of("Unicode and tabs", "\t中😀\nz", 4, 1, "\t中😀\n\t中😀\nz", 9, 6, 1),
                Arguments.of("SQL is not interpreted", "'a\nb';\nDROP TABLE x;", 1, 4,
                        "'a\nb';\n'a\nb';\nDROP TABLE x;", 8, 11, 2));
    }

    @ParameterizedTest(name = "{0}") @MethodSource("copies")
    void oneInsertionCopiesWholeLinesAndMovesExactSelection(String name, String text, int anchor, int caret,
                                                            String expected, int nextAnchor, int nextCaret, int lines) {
        var plan = SqlDuplicateLines.plan(text, anchor, caret);
        assertEquals(expected, text.substring(0, plan.position()) + plan.insertion() + text.substring(plan.position()));
        assertEquals(nextAnchor, plan.anchor()); assertEquals(nextCaret, plan.caret()); assertEquals(lines, plan.lines());
        assertEquals(text.substring(Math.min(anchor, caret), Math.max(anchor, caret)),
                expected.substring(Math.min(plan.anchor(), plan.caret()), Math.max(plan.anchor(), plan.caret())));
    }

    @ParameterizedTest @ValueSource(ints = {9_999, 10_000, 10_001})
    void lineLimitHasExactBoundaryAndTrailingEmptyLineIsNotAccidentallyCopied(int lines) {
        String text = "x\n".repeat(lines);
        if (lines > 10_000) assertThrows(IllegalArgumentException.class, () -> SqlDuplicateLines.plan(text, text.length(), 0));
        else {
            var plan = SqlDuplicateLines.plan(text, text.length(), 0);
            assertEquals(lines, plan.lines()); assertEquals(text, plan.insertion());
            assertEquals(text.length() * 2, plan.anchor()); assertEquals(text.length(), plan.caret());
        }
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 1})
    void outputLengthLimitIsCheckedBeforeBuildingOversizedCopy(int offset) {
        // The first line adds exactly two units; total output is MAX + offset.
        String text = "x\n" + "y".repeat(SqlDuplicateLines.MAX_TEXT_LENGTH - 4 + offset);
        if (offset > 0) assertThrows(IllegalArgumentException.class, () -> SqlDuplicateLines.plan(text, 0, 0));
        else {
            var plan = SqlDuplicateLines.plan(text, 0, 0);
            assertEquals("x\n", plan.insertion()); assertEquals(2, plan.caret());
            assertEquals(SqlDuplicateLines.MAX_TEXT_LENGTH + offset, text.length() + plan.insertion().length());
        }
    }

    @Test void invalidInputsNeverProducePartialPlans() {
        assertThrows(NullPointerException.class, () -> SqlDuplicateLines.plan(null, 0, 0));
        for (int[] range : new int[][]{{-1, 0}, {0, -1}, {4, 0}, {0, 4}})
            assertThrows(IllegalArgumentException.class, () -> SqlDuplicateLines.plan("abc", range[0], range[1]));
        assertThrows(IllegalArgumentException.class, () -> SqlDuplicateLines.plan("a\r\nb", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> SqlDuplicateLines.plan("a\rb", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> SqlDuplicateLines.plan("x".repeat(SqlDuplicateLines.MAX_TEXT_LENGTH + 1), 0, 0));
    }
}
