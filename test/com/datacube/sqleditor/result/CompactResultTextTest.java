package com.datacube.sqleditor.result;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class CompactResultTextTest {
    @ParameterizedTest @MethodSource("examples")
    void rendersOnlyAnExplicitSingleLinePreview(String input, String expected) {
        assertEquals(expected, CompactResultText.preview(input));
    }

    static Stream<Arguments> examples() {
        return Stream.of(Arguments.of("", ""), Arguments.of("NULL", "NULL"),
                Arguments.of("中😀\tvalue", "中😀\tvalue"), Arguments.of("<b>text</b>", "<b>text</b>"),
                Arguments.of("first\r\nsecond", "first ↵ …"), Arguments.of("\n", " ↵ …"),
                Arguments.of("x".repeat(255) + "😀", "x".repeat(255) + " …"),
                Arguments.of("x".repeat(254) + "😀", "x".repeat(254) + "😀"),
                Arguments.of("x".repeat(256) + "\nsecond", "x".repeat(256) + " ↵ …"));
    }

    @ParameterizedTest @ValueSource(strings = {"\r", "\n", "\u0085", "\u2028", "\u2029", "\u000b", "\f"})
    void treatsEveryVisualLineBreakAsAPreviewBoundary(String separator) {
        assertEquals("first ↵ …", CompactResultText.preview("first" + separator + "last"));
    }

    @ParameterizedTest @ValueSource(ints = {255, 256, 257, 65_537})
    void onlyAddsLengthMarkerWhenContentIsActuallyOmitted(int length) {
        String input = "x".repeat(length);
        assertEquals("x".repeat(Math.min(length, 256)) + (length > 256 ? " …" : ""), CompactResultText.preview(input));
    }
}
