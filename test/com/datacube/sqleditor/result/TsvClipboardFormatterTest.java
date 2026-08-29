package com.datacube.sqleditor.result;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TsvClipboardFormatterTest {
    @Test
    void rectangleIncludesGapsAndEscapesSpreadsheetSensitiveText() {
        String tsv = TsvClipboardFormatter.rectangle(
                List.of("A", "B", "C"),
                List.of(List.of("x", "a\tb", "z"), List.of("q", "line\n2", "w")),
                Set.of(new TsvClipboardFormatter.CellRef(0, 0),
                        new TsvClipboardFormatter.CellRef(0, 2),
                        new TsvClipboardFormatter.CellRef(1, 1)),
                true);
        assertEquals("A\tB\tC\nx\t\tz\n\t\"line\n2\"\t", tsv);
    }

    @Test
    void rowsSortByIndexAndFormatNullQuotesAndTabs() {
        String tsv = TsvClipboardFormatter.rows(
                List.of("A", "B"),
                List.of(java.util.Arrays.asList("plain", null), List.of("say \"hi\"", "a\tb")),
                Set.of(1, 0), false);

        assertEquals("plain\t\n\"say \"\"hi\"\"\"\t\"a\tb\"", tsv);
    }

    @Test
    void rowsIncludeHeaderAndUseRowIndexOrderForUnorderedSelection() {
        String tsv = TsvClipboardFormatter.rows(
                List.of("A", "B"), List.of(List.of("first", "1"), List.of("second", "2")),
                Set.of(1, 0), true);

        assertEquals("A\tB\nfirst\t1\nsecond\t2", tsv);
    }
}
