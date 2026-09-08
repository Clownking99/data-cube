package com.datacube.sqleditor;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlExecutionRangeTest {
    static Stream<Arguments> ranges() {
        return Stream.of(
                Arguments.of("", 0, 0, false, ""),
                Arguments.of("one;\n\t two;", 2, 2, false, "one;\n\t two;"),
                Arguments.of("one;\n\t two;", 4, 7, false, "one;\n\t two;"),
                Arguments.of("one;\n\t two;", 7, 11, true, "two;"),
                Arguments.of("one;\n\t two;", 11, 7, true, "two;"),
                Arguments.of("  one;  two;", 0, 8, true, "  one;  "),
                Arguments.of("中😀\r\nSQL", 0, 5, true, "中😀\r\n"),
                Arguments.of("\u2003select", 0, 1, true, "\u2003"),
                Arguments.of("\u001fselect", 0, 1, false, "\u001fselect"),
                Arguments.of("x", 0, 1, true, "x"));
    }

    @ParameterizedTest @MethodSource("ranges")
    void preservesTheExistingSelectionOrAllContract(String text, int anchor, int caret,
                                                   boolean selection, String expected) {
        var range = SqlExecutionRange.resolve(text, anchor, caret);
        assertEquals(selection, range.selection());
        assertEquals(expected, range.extract(text));
        assertEquals(selection ? Math.min(anchor, caret) : 0, range.start());
        assertEquals(selection ? Math.max(anchor, caret) : text.length(), range.end());
    }

    @ParameterizedTest @CsvSource({"-1,0", "0,-1", "0,8", "8,0"})
    void invalidOffsetsCannotBecomeAValidExecution(int anchor, int caret) {
        assertThrows(IllegalArgumentException.class, () -> SqlExecutionRange.resolve("select;", anchor, caret));
    }

    @Test void nullOrMalformedRangesAreRejected() {
        assertThrows(NullPointerException.class, () -> SqlExecutionRange.resolve(null, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new SqlExecutionRange(-1, 2, true));
        assertThrows(IllegalArgumentException.class, () -> new SqlExecutionRange(2, 1, true));
    }
}
