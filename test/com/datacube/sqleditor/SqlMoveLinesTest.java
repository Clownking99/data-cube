package com.datacube.sqleditor;

import com.datacube.sqleditor.SqlMoveLines.Direction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlMoveLinesTest {
    static Stream<Arguments> moves() {
        return Stream.of(
                Arguments.of("a\nbb\nccc", 0, 0, Direction.DOWN, "bb\na\nccc", 3, 3, 1),
                Arguments.of("a\nbb\nccc", 3, 3, Direction.UP, "bb\na\nccc", 1, 1, 1),
                Arguments.of("a\nbb\nccc", 0, 2, Direction.DOWN, "bb\na\nccc", 3, 5, 1),
                Arguments.of("a\nbb\nccc", 5, 2, Direction.DOWN, "a\nccc\nbb", 8, 6, 1),
                Arguments.of("a\nbb\nccc", 7, 3, Direction.UP, "bb\nccc\na", 5, 1, 2),
                Arguments.of("a\nbb\nccc", 1, 1, Direction.DOWN, "bb\na\nccc", 4, 4, 1),
                Arguments.of("a\nbb\nccc", 2, 2, Direction.UP, "bb\na\nccc", 0, 0, 1),
                Arguments.of("a\n", 2, 2, Direction.UP, "\na", 0, 0, 1),
                Arguments.of("\nb", 0, 0, Direction.DOWN, "b\n", 2, 2, 1),
                Arguments.of("a\nb\n", 0, 4, Direction.DOWN, "\na\nb", 1, 4, 2),
                Arguments.of("head\n  中😀\n\ttail", 7, 9, Direction.DOWN, "head\n\ttail\n  中😀", 13, 15, 1));
    }

    @ParameterizedTest @MethodSource("moves")
    void rotatesOnlyBodiesAndMapsExactSelection(String before, int anchor, int caret, Direction direction,
                                               String after, int nextAnchor, int nextCaret, int lines) {
        var plan = SqlMoveLines.plan(before, anchor, caret, direction);
        assertTrue(plan.movable()); assertEquals(after, apply(before, plan));
        assertEquals(nextAnchor, plan.anchor()); assertEquals(nextCaret, plan.caret()); assertEquals(lines, plan.lines());
        assertEquals(before.length(), after.length());
        for (var edit : plan.edits()) {
            assertFalse(edit.text().contains("\n")); assertFalse(before.substring(edit.start(), edit.end()).contains("\n"));
        }
    }

    static Stream<Arguments> edges() {
        return Stream.of(Arguments.of("", 0, 0, Direction.UP), Arguments.of("", 0, 0, Direction.DOWN),
                Arguments.of("single", 3, 1, Direction.UP), Arguments.of("single", 0, 6, Direction.DOWN),
                Arguments.of("a\nb", 0, 0, Direction.UP), Arguments.of("a\nb", 3, 3, Direction.DOWN),
                Arguments.of("a\nb", 3, 0, Direction.UP), Arguments.of("a\nb", 3, 0, Direction.DOWN));
    }
    @ParameterizedTest @MethodSource("edges")
    void boundaryIsNotAMutation(String text, int anchor, int caret, Direction direction) {
        var plan = SqlMoveLines.plan(text, anchor, caret, direction);
        assertFalse(plan.movable()); assertTrue(plan.edits().isEmpty());
        assertEquals(anchor, plan.anchor()); assertEquals(caret, plan.caret()); assertEquals(text, apply(text, plan));
    }

    @Test void identicalBodiesMoveSelectionWithoutInventingTextEdits() {
        var plan = SqlMoveLines.plan("same\nsame", 1, 3, Direction.DOWN);
        assertTrue(plan.movable()); assertTrue(plan.edits().isEmpty()); assertEquals(6, plan.anchor()); assertEquals(8, plan.caret());
    }

    @ParameterizedTest @ValueSource(ints = {9999, 10000, 10001})
    void selectedLineLimitDoesNotCountExchangedNeighbor(int lines) {
        String text = "x\n".repeat(lines) + "tail";
        if (lines > SqlMoveLines.MAX_LINES) {
            assertThrows(IllegalArgumentException.class, () -> SqlMoveLines.plan(text, 0, lines * 2, Direction.DOWN)); return;
        }
        var plan = SqlMoveLines.plan(text, 0, lines * 2, Direction.DOWN);
        assertEquals("tail\n" + "x\n".repeat(lines - 1) + "x", apply(text, plan));
        assertEquals(lines, plan.lines()); assertEquals(5, plan.anchor()); assertEquals(text.length(), plan.caret());
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 1})
    void textLengthLimitRejectsWholePlanOnlyAboveBoundary(int offset) {
        String text = "a\n" + "x".repeat(SqlMoveLines.MAX_TEXT_LENGTH + offset - 2);
        if (offset > 0) assertThrows(IllegalArgumentException.class, () -> SqlMoveLines.plan(text, 0, 0, Direction.DOWN));
        else {
            var plan = SqlMoveLines.plan(text, 0, 0, Direction.DOWN);
            assertEquals(text.substring(2) + "\na", apply(text, plan)); assertEquals(text.length() - 1, plan.caret());
        }
    }

    @Test void rejectsInvalidPositionsUnnormalizedInputAndMissingDirection() {
        assertThrows(IllegalArgumentException.class, () -> SqlMoveLines.plan("abc", -1, 0, Direction.UP));
        assertThrows(IllegalArgumentException.class, () -> SqlMoveLines.plan("abc", 0, -1, Direction.DOWN));
        assertThrows(IllegalArgumentException.class, () -> SqlMoveLines.plan("abc", 4, 0, Direction.UP));
        assertThrows(IllegalArgumentException.class, () -> SqlMoveLines.plan("abc", 0, 4, Direction.DOWN));
        assertThrows(IllegalArgumentException.class, () -> SqlMoveLines.plan("a\r\nb", 0, 0, Direction.DOWN));
        assertThrows(NullPointerException.class, () -> SqlMoveLines.plan("abc", 0, 0, null));
        assertThrows(NullPointerException.class, () -> SqlMoveLines.plan(null, 0, 0, Direction.UP));
    }

    private static String apply(String before, SqlMoveLines.Plan plan) {
        var result = new StringBuilder(before);
        for (int i = plan.edits().size() - 1; i >= 0; i--) {
            var edit = plan.edits().get(i); result.replace(edit.start(), edit.end(), edit.text());
        }
        return result.toString();
    }
}
